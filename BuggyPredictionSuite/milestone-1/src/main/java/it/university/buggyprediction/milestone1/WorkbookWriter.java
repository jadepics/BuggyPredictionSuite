package it.university.buggyprediction.milestone1;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import static it.university.buggyprediction.milestone1.DatasetText.*;
import static it.university.buggyprediction.milestone1.ExcelSupport.*;
import static it.university.buggyprediction.milestone1.ExcelSupport.setString;
import static it.university.buggyprediction.milestone1.ExcelSupport.writeHeader;

record WorkbookData(
        ReleaseCatalog releaseCatalog,
        List<Release> selectedReleases,
        List<Ticket> tickets,
        List<ClassMetrics> datasetRows,
        List<ExcludedTicket> excludedTickets,
        double releaseFraction,
        Instant startedAt,
        Instant completedAt,
        String repositoryHead) {
}


interface SheetWriter {
    void write(Workbook workbook, Styles styles, WorkbookData data);
}


final class WorkbookWriter {
    private final List<SheetWriter> sheetWriters = List.of(
            new DatasetSheetWriter(),
            new TicketsSheetWriter(),
            new TicketCommitsSheetWriter(),
            new CommitFilesSheetWriter(),
            new ReleasesSheetWriter(),
            new ReleaseAnomaliesSheetWriter(),
            new ExcludedTicketsSheetWriter(),
            new MetadataSheetWriter());

    void write(final Path outputFile, final WorkbookData data) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Styles styles = new Styles(workbook);
            sheetWriters.forEach(writer -> writer.write(workbook, styles, data));
            save(workbook, outputFile);
        }
    }

    private static void save(final Workbook workbook, final Path outputFile) throws IOException {
        try (OutputStream output = Files.newOutputStream(outputFile)) {
            workbook.write(output);
        } catch (java.nio.file.FileSystemException exception) {
            throw new IOException(
                    "Impossibile scrivere " + outputFile
                            + ". Chiudere il file in Excel e rieseguire.", exception);
        }
    }
}


final class ExcelSupport {
    static final int EXCEL_MAX_CELL_TEXT_LENGTH = 32_767;
    static final String TRUNCATION_SUFFIX =
            "\n[TRUNCATED: Excel cell limit reached; see normalized workbook sheets]";

    private ExcelSupport() {
    }

    static void writeHeader(
            final Sheet sheet,
            final String[] headers,
            final Styles styles) {
        Row header = sheet.createRow(0);
        header.setHeightInPoints(28);
        for (int column = 0; column < headers.length; column++) {
            Cell cell = header.createCell(column);
            cell.setCellValue(headers[column]);
            cell.setCellStyle(styles.header);
        }
    }

    static void finishSheet(
            final Sheet sheet,
            final int columnCount,
            final int rowCount,
            final int[] widths) {
        sheet.createFreezePane(0, 1);
        if (rowCount > 1) {
            sheet.setAutoFilter(new CellRangeAddress(
                    0,
                    rowCount - 1,
                    0,
                    columnCount - 1));
        }
        for (int column = 0; column < columnCount; column++) {
            int width = column < widths.length ? widths[column] : 16;
            width = Math.max(8, Math.min(width, 120));
            sheet.setColumnWidth(column, width * 256);
        }
    }

    static int[] datasetColumnWidths() {
        int[] widths = new int[36];
        Arrays.fill(widths, 14);
        widths[0] = 20;
        widths[1] = 18;
        widths[2] = 14;
        widths[3] = 30;
        widths[4] = 75;
        widths[24] = 22;
        widths[27] = 60;
        widths[28] = 24;
        widths[29] = 65;
        widths[32] = 45;
        widths[33] = 45;
        widths[34] = 24;
        widths[35] = 60;
        return widths;
    }

    static int[] ticketColumnWidths() {
        return new int[]{
                18, 65, 16, 18, 14, 22, 22, 20, 16,
                18, 55, 18, 55, 18, 55, 20, 16, 20, 16, 26,
                18, 20, 18, 18, 55, 22, 70, 55, 55, 26, 22
        };
    }

    static int[] commitColumnWidths() {
        return new int[]{
                18, 42, 70, 22, 22, 20, 16, 22, 20, 16,
                18, 20, 85, 24, 60, 50
        };
    }

    static void setString(
            final Row row,
            final int column,
            final String value,
            final CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(excelSafeText(value));
        cell.setCellStyle(style);
    }

    static String excelSafeText(final String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= EXCEL_MAX_CELL_TEXT_LENGTH) {
            return value;
        }

        int maximumPrefixLength =
                EXCEL_MAX_CELL_TEXT_LENGTH - TRUNCATION_SUFFIX.length();
        return value.substring(0, Math.max(0, maximumPrefixLength))
                + TRUNCATION_SUFFIX;
    }

    static void setInteger(
            final Row row,
            final int column,
            final int value,
            final CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    static void setNullableInteger(
            final Row row,
            final int column,
            final Integer value,
            final CellStyle style) {
        Cell cell = row.createCell(column);
        if (value != null) {
            cell.setCellValue(value);
        }
        cell.setCellStyle(style);
    }

    static void setDouble(
            final Row row,
            final int column,
            final double value,
            final CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    static void setNullableDouble(
            final Row row,
            final int column,
            final Double value,
            final CellStyle style) {
        Cell cell = row.createCell(column);
        if (value != null) {
            cell.setCellValue(value);
        }
        cell.setCellStyle(style);
    }

    static void setBoolean(
            final Row row,
            final int column,
            final boolean value,
            final CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    static void setDate(
            final Row row,
            final int column,
            final LocalDate value,
            final CellStyle style) {
        Cell cell = row.createCell(column);
        if (value != null) {
            cell.setCellValue(value);
        }
        cell.setCellStyle(style);
    }

    static void setDateTime(
            final Row row,
            final int column,
            final OffsetDateTime value,
            final CellStyle style) {
        Cell cell = row.createCell(column);
        if (value != null) {
            cell.setCellValue(value.toLocalDateTime());
        }
        cell.setCellStyle(style);
    }

    static String version(final Release release) {
        return release == null ? "" : release.version;
    }

    static LocalDate releaseDate(final Release release) {
        return release == null ? null : release.releaseDate;
    }
}


final class Styles {

    final CellStyle header;
    final CellStyle text;
    final CellStyle wrapText;
    final CellStyle integer;
    final CellStyle decimal;
    final CellStyle date;
    final CellStyle dateTime;
    final CellStyle booleanStyle;
    final CellStyle buggyYes;
    final CellStyle buggyNo;
    final CellStyle error;
    final CellStyle warning;
    final CellStyle ok;
    final CellStyle metadataKey;

    Styles(final Workbook workbook) {
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());

        header = workbook.createCellStyle();
        header.setFont(headerFont);
        header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        header.setAlignment(HorizontalAlignment.CENTER);
        header.setVerticalAlignment(VerticalAlignment.CENTER);
        header.setWrapText(true);
        addBorders(header);

        text = workbook.createCellStyle();
        text.setVerticalAlignment(VerticalAlignment.TOP);
        addBorders(text);

        wrapText = workbook.createCellStyle();
        wrapText.cloneStyleFrom(text);
        wrapText.setWrapText(true);

        integer = workbook.createCellStyle();
        integer.cloneStyleFrom(text);
        integer.setDataFormat(workbook.createDataFormat().getFormat("0"));

        decimal = workbook.createCellStyle();
        decimal.cloneStyleFrom(text);
        decimal.setDataFormat(workbook.createDataFormat().getFormat("0.000000"));

        date = workbook.createCellStyle();
        date.cloneStyleFrom(text);
        date.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));

        dateTime = workbook.createCellStyle();
        dateTime.cloneStyleFrom(text);
        dateTime.setDataFormat(workbook.createDataFormat()
                .getFormat("yyyy-mm-dd hh:mm:ss"));

        booleanStyle = workbook.createCellStyle();
        booleanStyle.cloneStyleFrom(text);
        booleanStyle.setAlignment(HorizontalAlignment.CENTER);

        buggyYes = coloredStyle(workbook, IndexedColors.CORAL);
        buggyNo = coloredStyle(workbook, IndexedColors.LIGHT_GREEN);
        error = coloredStyle(workbook, IndexedColors.ROSE);
        warning = coloredStyle(workbook, IndexedColors.LIGHT_YELLOW);
        ok = coloredStyle(workbook, IndexedColors.LIGHT_GREEN);

        Font keyFont = workbook.createFont();
        keyFont.setBold(true);
        metadataKey = workbook.createCellStyle();
        metadataKey.cloneStyleFrom(wrapText);
        metadataKey.setFont(keyFont);
        metadataKey.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        metadataKey.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    }

    private static CellStyle coloredStyle(
            final Workbook workbook,
            final IndexedColors color) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(color.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        style.setWrapText(true);
        addBorders(style);
        return style;
    }

    private static void addBorders(final CellStyle style) {
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }
}


final class DatasetSheetWriter implements SheetWriter {
    @Override
    public void write(
            final Workbook workbook,
            final Styles styles,
            final WorkbookData data) {
        Sheet sheet = workbook.createSheet("Dataset");
        String[] headers = {
                "Project", "Release", "ReleaseDate", "ClassName", "ClassPath",
                "LOC", "LOCTouched", "NR", "Nfix", "Nauth",
                "LOCAdded", "MaxLOCAdded", "AverageLOCAdded",
                "CLOC", "WMC",
                "MaxChurn", "AverageChurn",
                "ChangeSetSize", "NPM", "MaxChangeSet", "AverageChangeSet",
                "Age", "WeightedAge", "AGE",
                "SmellSourceRelease", "NSmells", "NPMDRuleTypes", "PMDRules",
                "PMDAnalysisStatus", "PMDAnalysisWarning",
                "Buggy", "BugTicketCount", "BugTickets", "FixCommits",
                "ConsistencyStatus", "AnalysisWarning"
        };
        writeHeader(sheet, headers, styles);

        int rowIndex = 1;
        for (ClassMetrics value : data.datasetRows()) {
            Row row = sheet.createRow(rowIndex++);
            int column = 0;
            setString(row, column++, value.project, styles.text);
            setString(row, column++, value.release.version, styles.text);
            setDate(row, column++, value.release.releaseDate, styles.date);
            setString(row, column++, value.className, styles.text);
            setString(row, column++, value.classPath, styles.text);
            setInteger(row, column++, value.loc, styles.integer);
            setInteger(row, column++, value.locTouched, styles.integer);
            setInteger(row, column++, value.revisions, styles.integer);
            setInteger(row, column++, value.defectFixes, styles.integer);
            setInteger(row, column++, value.authors, styles.integer);
            setInteger(row, column++, value.locAdded, styles.integer);
            setInteger(row, column++, value.maxLocAdded, styles.integer);
            setDouble(row, column++, value.averageLocAdded, styles.decimal);
            setInteger(row, column++, value.cloc, styles.integer);
            setInteger(row, column++, value.wmc, styles.integer);
            setInteger(row, column++, value.maxChurn, styles.integer);
            setDouble(row, column++, value.averageChurn, styles.decimal);
            setInteger(row, column++, value.changeSetSize, styles.integer);
            setInteger(row, column++, value.npm, styles.integer);
            setInteger(row, column++, value.maxChangeSet, styles.integer);
            setDouble(row, column++, value.averageChangeSet, styles.decimal);
            setDouble(row, column++, value.age, styles.decimal);
            setDouble(row, column++, value.weightedAge, styles.decimal);
            setDouble(row, column++, value.averageChangeInterval, styles.decimal);
            setString(row, column++, value.smellSourceRelease, styles.text);
            setNullableInteger(row, column++, value.nSmells, styles.integer);
            setNullableInteger(row, column++, value.nPmdRuleTypes, styles.integer);
            setString(row, column++, value.pmdRules, styles.wrapText);
            setString(row, column++, value.pmdAnalysisStatus,
                    PmdSmellSnapshot.STATUS_ERROR.equals(value.pmdAnalysisStatus)
                            ? styles.error
                            : styles.text);
            setString(row, column++, value.pmdAnalysisWarning, styles.wrapText);
            setString(row, column++, value.buggy ? "Yes" : "No",
                    value.buggy ? styles.buggyYes : styles.buggyNo);
            setInteger(row, column++, value.bugTickets.size(), styles.integer);
            setString(row, column++, joinStrings(value.bugTickets), styles.wrapText);
            setString(row, column++, joinStrings(value.fixCommits), styles.wrapText);
            setString(row, column++, value.consistencyStatus, styles.text);
            setString(row, column, value.analysisWarning, styles.wrapText);
        }

        finishSheet(sheet, headers.length, rowIndex, datasetColumnWidths());
    }
}


final class TicketsSheetWriter implements SheetWriter {
    @Override
    public void write(final Workbook workbook, final Styles styles, final WorkbookData data) {
                Sheet sheet = workbook.createSheet("Tickets");
                String[] headers = {
                        "ticketID", "summary", "status", "resolution", "priority",
                        "createDate", "closedDate",
                        "openingVersion", "openingVersionDate",
                        "affectedVersionCountRaw", "affectedVersionsRaw",
                        "affectedVersionCount", "affectedVersions",
                        "fixedVersionCountRaw", "fixedVersionsRaw",
                        "fixedVersion", "fixedVersionDate",
                        "injectedVersion", "injectedVersionDate", "injectedVersionSource",
                        "proportionUsed", "totalProportionObservations",
                        "commitCandidateCount", "validFixCommitCount", "selectedFixCommits",
                        "consistencyStatus", "violations", "dataGaps", "warnings",
                        "labelingStatus", "labeledClassReleaseRows"
                };
                writeHeader(sheet, headers, styles);

                List<Ticket> ordered = data.tickets().stream()
                        .sorted(Comparator
                                .comparing((Ticket ticket) -> ticket.issue.createDate,
                                        Comparator.nullsLast(Comparator.naturalOrder()))
                                .thenComparing(ticket -> ticket.issue.key))
                        .toList();

                int rowIndex = 1;
                for (Ticket ticket : ordered) {
                    Row row = sheet.createRow(rowIndex++);
                    int column = 0;
                    setString(row, column++, ticket.issue.key, styles.text);
                    setString(row, column++, ticket.issue.summary, styles.wrapText);
                    setString(row, column++, ticket.issue.status, styles.text);
                    setString(row, column++, ticket.issue.resolution, styles.text);
                    setString(row, column++, ticket.issue.priority, styles.text);
                    setDateTime(row, column++, ticket.issue.createDate, styles.dateTime);
                    setDateTime(row, column++, ticket.issue.closedDate, styles.dateTime);
                    setString(row, column++, version(ticket.openingVersion), styles.text);
                    setDate(row, column++, releaseDate(ticket.openingVersion), styles.date);
                    setInteger(row, column++, ticket.issue.affectedVersionsRaw.size(), styles.integer);
                    setString(row, column++, joinStrings(ticket.issue.affectedVersionsRaw), styles.wrapText);
                    setInteger(row, column++, ticket.affectedVersions.size(), styles.integer);
                    setString(row, column++, joinVersions(ticket.affectedVersions), styles.wrapText);
                    setInteger(row, column++, ticket.issue.fixedVersionsRaw.size(), styles.integer);
                    setString(row, column++, joinStrings(ticket.issue.fixedVersionsRaw), styles.wrapText);
                    setString(row, column++, version(ticket.fixedVersion), styles.text);
                    setDate(row, column++, releaseDate(ticket.fixedVersion), styles.date);
                    setString(row, column++, version(ticket.injectedVersion), styles.text);
                    setDate(row, column++, releaseDate(ticket.injectedVersion), styles.date);
                    setString(row, column++, ticket.injectedVersionSource, styles.text);
                    setNullableDouble(row, column++, ticket.proportionUsed, styles.decimal);
                    setInteger(row, column++, ticket.totalProportionObservationCount, styles.integer);
                    setInteger(row, column++, ticket.commitCandidates.size(), styles.integer);
                    setInteger(row, column++, ticket.validCommits.size(), styles.integer);
                    setString(row, column++, ticket.validCommits.stream()
                            .map(commit -> commit.hash)
                            .collect(Collectors.joining(" | ")), styles.wrapText);
                    setString(row, column++, ticket.consistencyStatus.name(),
                            ticket.consistencyStatus == ConsistencyStatus.INCONSISTENT
                                    ? styles.error
                                    : ticket.consistencyStatus == ConsistencyStatus.NOT_FULLY_CHECKABLE
                                      ? styles.warning
                                      : styles.ok);
                    setString(row, column++, joinStrings(ticket.violations), styles.wrapText);
                    setString(row, column++, joinStrings(ticket.dataGaps), styles.wrapText);
                    setString(row, column++, joinStrings(ticket.warnings), styles.wrapText);
                    setString(row, column++, ticket.labelingStatus.name(), styles.text);
                    setInteger(row, column, ticket.labeledClassReleaseRows, styles.integer);
                }

                finishSheet(sheet, headers.length, rowIndex, ticketColumnWidths());
    
    }
}


final class TicketCommitsSheetWriter implements SheetWriter {
    @Override
    public void write(final Workbook workbook, final Styles styles, final WorkbookData data) {
                Sheet sheet = workbook.createSheet("TicketCommits");
                String[] headers = {
                        "ticketID", "commitHash", "commitSubject", "authorDate", "committerDate",
                        "openingVersion", "openingVersionDate", "createDate",
                        "fixedVersion", "fixedVersionDate",
                        "changedFileCount", "changedJavaFileCount", "changedJavaFiles",
                        "temporalStatus", "violations", "warnings"
                };
                writeHeader(sheet, headers, styles);

                int rowIndex = 1;
                for (Ticket ticket : data.tickets()) {
                    for (GitCommit commit : ticket.commitCandidates) {
                        Row row = sheet.createRow(rowIndex++);
                        int column = 0;
                        setString(row, column++, ticket.issue.key, styles.text);
                        setString(row, column++, commit.hash, styles.text);
                        setString(row, column++, commit.subject, styles.wrapText);
                        setDateTime(row, column++, commit.authorDate, styles.dateTime);
                        setDateTime(row, column++, commit.committerDate, styles.dateTime);
                        setString(row, column++, version(ticket.openingVersion), styles.text);
                        setDate(row, column++, releaseDate(ticket.openingVersion), styles.date);
                        setDateTime(row, column++, ticket.issue.createDate, styles.dateTime);
                        setString(row, column++, version(ticket.fixedVersion), styles.text);
                        setDate(row, column++, releaseDate(ticket.fixedVersion), styles.date);
                        setInteger(row, column++, commit.fileChanges.size(), styles.integer);
                        Set<String> productionJavaPaths = commit.productionJavaPaths();
                        setInteger(row, column++, productionJavaPaths.size(), styles.integer);
                        setString(
                                row,
                                column++,
                                summarizeCommitJavaPaths(productionJavaPaths),
                                styles.wrapText);
                        setString(row, column++, commit.temporalValid ? "VALID_FIX_COMMIT" : "INVALID",
                                commit.temporalValid ? styles.ok : styles.error);
                        setString(row, column++, joinStrings(commit.violations), styles.wrapText);
                        setString(row, column, joinStrings(commit.warnings), styles.wrapText);
                    }
                }

                finishSheet(sheet, headers.length, rowIndex, commitColumnWidths());
    }

    private static String summarizeCommitJavaPaths(
            final Collection<String> paths) {
        String complete = joinStrings(paths);
        if (complete.length() <= EXCEL_MAX_CELL_TEXT_LENGTH) {
            return complete;
        }
        return paths.size()
                + " production Java files; full list available in CommitFiles sheet";
    }
}


final class CommitFilesSheetWriter implements SheetWriter {
    @Override
    public void write(final Workbook workbook, final Styles styles, final WorkbookData data) {
                Sheet sheet = workbook.createSheet("CommitFiles");
                String[] headers = {
                        "ticketID", "commitHash", "commitSubject", "fileStatus",
                        "oldPath", "newPath", "oldPathIsProductionJava",
                        "newPathIsProductionJava"
                };
                writeHeader(sheet, headers, styles);

                int rowIndex = 1;
                for (Ticket ticket : data.tickets()) {
                    for (GitCommit commit : ticket.commitCandidates) {
                        for (FileChange change : commit.fileChanges) {
                            Row row = sheet.createRow(rowIndex++);
                            int column = 0;
                            setString(row, column++, ticket.issue.key, styles.text);
                            setString(row, column++, commit.hash, styles.text);
                            setString(row, column++, commit.subject, styles.wrapText);
                            setString(row, column++, change.status(), styles.text);
                            setString(row, column++, change.oldPath(), styles.wrapText);
                            setString(row, column++, change.newPath(), styles.wrapText);
                            setBoolean(
                                    row,
                                    column++,
                                    ProductionJavaPathFilter.isProductionJavaPath(change.oldPath()),
                                    styles.booleanStyle);
                            setBoolean(
                                    row,
                                    column,
                                    ProductionJavaPathFilter.isProductionJavaPath(change.newPath()),
                                    styles.booleanStyle);
                        }
                    }
                }

                finishSheet(
                        sheet,
                        headers.length,
                        rowIndex,
                        new int[]{18, 42, 70, 14, 90, 90, 24, 24});
    
    }
}


final class ReleasesSheetWriter implements SheetWriter {
    @Override
    public void write(final Workbook workbook, final Styles styles, final WorkbookData data) {
                Sheet sheet = workbook.createSheet("Releases");
                String[] headers = {
                        "sequence",
                        "jiraVersionID",
                        "version",
                        "canonicalVersion",
                        "jiraReleaseDate",
                        "effectiveReleaseDate",
                        "releaseDateSource",
                        "releaseDateCorrected",
                        "releaseDateCorrectionReason",
                        "releaseDateEvidenceURL",
                        "gitTag",
                        "tagCommitHash",
                        "tagCommitDate",
                        "archived",
                        "selectedFirst33Percent",
                        "productionJavaFileCount"
                };
                writeHeader(sheet, headers, styles);

                int rowIndex = 1;
                for (Release release : data.releaseCatalog().allReleases()) {
                    Row row = sheet.createRow(rowIndex++);
                    int column = 0;
                    setInteger(row, column++, release.sequence, styles.integer);
                    setString(row, column++, release.jiraId, styles.text);
                    setString(row, column++, release.version, styles.text);
                    setString(row, column++, release.canonicalVersion, styles.text);
                    setDate(row, column++, release.jiraReleaseDate, styles.date);
                    setDate(row, column++, release.releaseDate, styles.date);
                    setString(row, column++, release.releaseDateSource, styles.text);
                    setBoolean(row, column++, release.releaseDateCorrected(), styles.booleanStyle);
                    setString(row, column++, release.releaseDateCorrectionReason, styles.wrapText);
                    setString(row, column++, release.releaseDateEvidenceUrl, styles.wrapText);
                    setString(row, column++, release.gitTag, styles.text);
                    setString(row, column++, release.tagCommitHash, styles.text);
                    setDate(row, column++, release.tagCommitDate, styles.date);
                    setBoolean(row, column++, release.archived, styles.booleanStyle);
                    setBoolean(row, column++, release.selectedForDataset, styles.booleanStyle);
                    setInteger(row, column, release.productionJavaFileCount, styles.integer);
                }

                finishSheet(sheet, headers.length, rowIndex,
                        new int[]{10, 16, 22, 24, 15, 17, 25, 20, 65, 65,
                                28, 42, 15, 12, 22, 24});
    
    }
}


final class ReleaseAnomaliesSheetWriter implements SheetWriter {
    @Override
    public void write(final Workbook workbook, final Styles styles, final WorkbookData data) {
                Sheet sheet = workbook.createSheet("ReleaseAnomalies");
                String[] headers = {
                        "anomalyType",
                        "severity",
                        "version",
                        "canonicalVersion",
                        "relatedVersion",
                        "jiraReleaseDate",
                        "effectiveReleaseDate",
                        "gitTagCommitDate",
                        "details",
                        "evidenceURL"
                };
                writeHeader(sheet, headers, styles);

                int rowIndex = 1;
                for (ReleaseAnomaly anomaly : data.releaseCatalog().releaseAnomalies()) {
                    Row row = sheet.createRow(rowIndex++);
                    int column = 0;
                    setString(row, column++, anomaly.type(), styles.text);
                    setString(row, column++, anomaly.severity(), styles.text);
                    setString(row, column++, anomaly.version(), styles.text);
                    setString(row, column++, anomaly.canonicalVersion(), styles.text);
                    setString(row, column++, anomaly.relatedVersion(), styles.text);
                    setDate(row, column++, anomaly.jiraReleaseDate(), styles.date);
                    setDate(row, column++, anomaly.effectiveReleaseDate(), styles.date);
                    setDate(row, column++, anomaly.tagCommitDate(), styles.date);
                    setString(row, column++, anomaly.details(), styles.wrapText);
                    setString(row, column, anomaly.evidenceUrl(), styles.wrapText);
                }

                finishSheet(sheet, headers.length, rowIndex,
                        new int[]{38, 16, 24, 26, 24, 16, 18, 18, 80, 70});
    
    }
}


final class ExcludedTicketsSheetWriter implements SheetWriter {
    @Override
    public void write(final Workbook workbook, final Styles styles, final WorkbookData data) {
                Sheet sheet = workbook.createSheet("ExcludedTickets");
                String[] headers = {
                        "ticketID", "exclusionReason", "consistencyStatus",
                        "violations", "dataGaps", "warnings"
                };
                writeHeader(sheet, headers, styles);

                int rowIndex = 1;
                for (ExcludedTicket excluded : data.excludedTickets()) {
                    Row row = sheet.createRow(rowIndex++);
                    setString(row, 0, excluded.ticketId, styles.text);
                    setString(row, 1, excluded.reason, styles.error);
                    setString(row, 2, excluded.consistencyStatus, styles.text);
                    setString(row, 3, excluded.violations, styles.wrapText);
                    setString(row, 4, excluded.dataGaps, styles.wrapText);
                    setString(row, 5, excluded.warnings, styles.wrapText);
                }

                finishSheet(sheet, headers.length, rowIndex,
                        new int[]{18, 42, 24, 65, 55, 55});
    
    }
}


final class MetadataSheetWriter implements SheetWriter {
    @Override
    public void write(final Workbook workbook, final Styles styles, final WorkbookData data) {
        Sheet sheet = workbook.createSheet("Metadata");
        writeHeader(sheet, new String[]{"Key", "Value"}, styles);

        Map<String, String> metadata = buildMetadata(data);
        int rowIndex = 1;
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            Row row = sheet.createRow(rowIndex++);
            setString(row, 0, entry.getKey(), styles.metadataKey);
            setString(row, 1, entry.getValue(), styles.wrapText);
        }

        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, rowIndex - 1, 0, 1));
        sheet.setColumnWidth(0, 34 * 256);
        sheet.setColumnWidth(1, 110 * 256);
    }

    private static Map<String, String> buildMetadata(final WorkbookData data) {
        Map<String, String> metadata = new LinkedHashMap<>();
        addExecutionMetadata(metadata, data);
        addLifecycleMetadata(metadata);
        addSourceMetadata(metadata);
        addMetricsMetadata(metadata);
        return metadata;
    }

    private static void addExecutionMetadata(
            final Map<String, String> metadata,
            final WorkbookData data) {
        metadata.put("Project", Milestone1Constants.PROJECT_NAME);
        metadata.put("JIRA project", Milestone1Constants.PROJECT_KEY);
        metadata.put("JQL", Milestone1Constants.JQL);
        metadata.put("Repository HEAD", data.repositoryHead());
        metadata.put("Started UTC", data.startedAt().toString());
        metadata.put("Completed UTC", data.completedAt().toString());
        metadata.put("Release fraction", Double.toString(data.releaseFraction()));
        metadata.put("All released JIRA versions with date",
                Integer.toString(data.releaseCatalog().allReleases().size()));
        metadata.put("Versions with Git tag",
                Integer.toString(data.releaseCatalog().taggedReleases().size()));
        metadata.put("Release date anomalies",
                Integer.toString(data.releaseCatalog().releaseAnomalies().size()));
        metadata.put("Selected versions", Integer.toString(data.selectedReleases().size()));
        metadata.put("Bug tickets", Integer.toString(data.tickets().size()));
        metadata.put("Dataset rows", Integer.toString(data.datasetRows().size()));
        metadata.put("Buggy rows", Long.toString(
                data.datasetRows().stream().filter(row -> row.buggy).count()));
        metadata.put("Excluded tickets", Integer.toString(data.excludedTickets().size()));
    }

    private static void addLifecycleMetadata(final Map<String, String> metadata) {
        metadata.put("OV rule", "Latest release with releaseDate <= ticket createDate; equality allowed.");
        metadata.put("AF construction rule",
                "AF_raw union all recognized FV except the most recent; duplicates removed.");
        metadata.put("AF consistency rule",
                "Every effective AF must satisfy IV<=AF<=FV. AF may be later than OV and ticket creation.");
        metadata.put("FV rule", "Most recent recognized Fixed Version by release chronology.");
        metadata.put("IV rule",
                "Oldest effective AF; if AF is absent, Proportion Total; fallback IV=OV only when no valid direct observation exists.");
        metadata.put("Proportion formula",
                "P=(FVindex-IVindex)/(FVindex-OVindex); one global mean is computed over all valid direct observations and reused for every missing IV.");
        metadata.put("Temporal consistency",
                "IV<=AF<=FV; IV<=OV<=FV; releaseDate(OV)<=createDate"
                        + "<=fixCommitDate<=releaseDate(FV); equal dates are valid.");
        metadata.put("Bug propagation",
                "Buggy from IV inclusive to the release immediately before FV: [IV,FV).");
        metadata.put("Feature extraction window",
                "Only the first ceil(N*releaseFraction) tagged releases are analyzed.");
        metadata.put("Labeling window",
                "100% of releases and tickets are used; a ticket contributes when IV is in the selected window.");
    }

    private static void addSourceMetadata(final Map<String, String> metadata) {
        metadata.put("Release date policy",
                "Raw JIRA dates are preserved; overrides require an official Apache source. "
                        + "Git tag dates are supporting evidence, not release-date replacements.");
        metadata.put("Known official date correction",
                "1.0.3-incubating: JIRA 2012-09-30 -> effective 2012-10-30.");
        metadata.put("Known correction source", Milestone1Constants.OFFICIAL_RELEASE_HISTORY_URL);
        metadata.put("Git history source",
                Milestone1Constants.SYNCOPE_REPOSITORY_URL
                        + " downloaded into a project-local bare mirror.");
        metadata.put("GitHub mirror refresh",
                "Enabled by default; disable with -Dmilestone.githubRefresh=false.");
        metadata.put("Source snapshot",
                "Temporary ZIP downloaded from GitHub for each tag; the wrapper directory "
                        + "is removed before paths are matched against commit paths.");
        metadata.put("Production Java filtering",
                "Excludes tests, integration tests, generated sources, target/build and test-like filenames.");
        metadata.put("Dataset granularity",
                "One row per release and production Java source file; top-level types are aggregated per file.");
        metadata.put("Commit ticket-key matching",
                "Exact SYNCOPE-<digits> matching; release names such as syncope-1.0.0 are excluded.");
        metadata.put("Labeling status",
                "USED=contributes; EXCLUDED=blocked/no class; OUTSIDE_SELECTED_RELEASE_WINDOW=IV outside window.");
        metadata.put("Commit file normalization",
                "CommitFiles stores one row per ticket, commit and file; TicketCommits uses compact summaries.");
        metadata.put("Excel text limit",
                "Text cells are truncated at 32767 characters; normalized details remain in dedicated sheets.");
    }

    private static void addMetricsMetadata(final Map<String, String> metadata) {
        metadata.put("Dataset feature metrics",
                "LOC,LOCTouched,NR,Nfix,Nauth,LOCAdded,MaxLOCAdded,"
                        + "AverageLOCAdded,CLOC,WMC,MaxChurn,AverageChurn,"
                        + "ChangeSetSize,NPM,MaxChangeSet,AverageChangeSet,Age,"
                        + "WeightedAge,AGE,NSmells,NPMDRuleTypes.");
        metadata.put("LOC",
                "Non-blank source lines containing code in the current release snapshot.");
        metadata.put("CLOC",
                "Non-blank source lines containing comments in the current release snapshot.");
        metadata.put("WMC",
                "Sum of cyclomatic complexity of methods and constructors in the current snapshot.");
        metadata.put("NPM",
                "Number of public methods declared in the current snapshot.");
        metadata.put("Process history boundary",
                "For each release, process metrics use commits reachable from its Git tag and not later than the effective release date.");
        metadata.put("Process path policy",
                "History is matched by the exact production Java path. Renames are not followed across old paths.");
        metadata.put("LOCTouched",
                "Sum across revisions of LOC added plus LOC deleted for the file.");
        metadata.put("Redundant process features removed",
                "Churn is omitted because it is identical to LOCTouched under the adopted definition; NUC is omitted because it is identical to NR at file-release granularity.");
        metadata.put("NR",
                "Number of distinct revisions that touched the file.");
        metadata.put("Nfix",
                "Number of revisions whose hash is a temporally valid fix commit for a JIRA Bug ticket.");
        metadata.put("Nauth",
                "Number of distinct author email addresses across file revisions.");
        metadata.put("LOCAdded",
                "Sum of LOC added across revisions; MaxLOCAdded and AverageLOCAdded are computed over revisions.");
        metadata.put("ChangeSetSize",
                "Cumulative sum, over revisions touching the file, of all files committed together. Max and average are also reported.");
        metadata.put("Age",
                "File age in days: interval from its first recorded revision to the effective release date.");
        metadata.put("WeightedAge",
                "Weighted mean age in days of file revisions, using LOC touched by each revision as weight.");
        metadata.put("AGE",
                "Average interval in days between consecutive revisions of the file; zero when fewer than two revisions exist.");
        metadata.put("Smell temporal policy",
                "For release R, PMD values are copied from the same class path in the previous selected release. The first release and newly introduced files receive NSmells=0, NPMDRuleTypes=0 and PMDAnalysisStatus=NO_PREVIOUS_SOURCE.");
        metadata.put("PMD version", PmdSmellAnalyzer.PMD_VERSION);
        metadata.put("PMD Java language version",
                PmdSmellAnalyzer.JAVA_LANGUAGE_VERSION
                        + " because the selected historical Syncope releases belong to the Java 8 era.");
        metadata.put("PMD ruleset",
                PmdSmellAnalyzer.RULESET_RESOURCE
                        + " packaged with the project; only its fixed structural/design rules contribute.");
        metadata.put("PMD suppression policy",
                "Only non-suppressed PMD violations are counted. Standard PMD suppressions such as NOPMD and @SuppressWarnings are respected.");
        metadata.put("NSmells definition",
                "Total number of non-suppressed PMD violations reported for the source file by the fixed Milestone 1 ruleset in the previous selected release.");
        metadata.put("NPMDRuleTypes definition",
                "Number of distinct PMD rule names that produced at least one violation on the source file in the previous selected release.");
        metadata.put("PMDRules",
                "Audit column listing each violated PMD rule and its occurrence count, for example CyclomaticComplexity(3) | GodClass(1). It is not intended as a direct numeric ML feature.");
        metadata.put("PMD analysis status",
                "OK=analysis completed, including zero violations; NO_PREVIOUS_SOURCE=no matching source in the previous selected release; ERROR=PMD could not analyze the file. ERROR counts are left blank rather than forced to zero.");
        metadata.put("Implementation architecture",
                "Single entry point with separate JIRA, GitHub, lifecycle, Proportion, metrics, labeling and Excel pipeline steps.");
    }

}

