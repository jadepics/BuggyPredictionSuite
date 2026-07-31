package it.university.buggyprediction.milestone1;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Esporta ogni foglio di milestone_1_dataset.xlsx in un file CSV indipendente.
 *
 * <p>Il foglio Dataset viene esportato come A.csv; il foglio Tickets come
 * Tickets.csv. Gli altri fogli mantengono il proprio nome.</p>
 */
public final class ExportMilestone1WorkbookToCsv {

    private static final Logger LOGGER =
            Logger.getLogger(ExportMilestone1WorkbookToCsv.class.getName());

    private static final String WORKBOOK_FILE_NAME =
            "milestone_1_dataset.xlsx";

    private ExportMilestone1WorkbookToCsv() {
    }

    public static void main(final String[] args) {
        try {
            Path workbookPath = resolveWorkbookPath();
            Path outputDirectory = resolveOutputDirectory(workbookPath);
            char separator = resolveSeparator();

            exportAllSheets(workbookPath, outputDirectory, separator);

            LOGGER.log(
                    Level.INFO,
                    "Esportazione completata. Directory CSV: {0}",
                    outputDirectory);
        } catch (Exception exception) {
            LOGGER.log(
                    Level.SEVERE,
                    "Esportazione dei fogli del workbook fallita",
                    exception);
            throw new IllegalStateException(
                    "Impossibile esportare il workbook in CSV",
                    exception);
        }
    }

    private static void exportAllSheets(
            final Path workbookPath,
            final Path outputDirectory,
            final char separator) throws IOException {

        Files.createDirectories(outputDirectory);

        try (InputStream input = Files.newInputStream(workbookPath);
             Workbook workbook = WorkbookFactory.create(input)) {

            FormulaEvaluator evaluator =
                    workbook.getCreationHelper().createFormulaEvaluator();
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            Set<String> usedFileNames = new HashSet<>();

            LOGGER.log(
                    Level.INFO,
                    "Workbook trovato: {0}; fogli presenti: {1}",
                    new Object[]{workbookPath, workbook.getNumberOfSheets()});

            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                Sheet sheet = workbook.getSheetAt(index);
                String fileName = uniqueFileName(
                        csvFileName(sheet.getSheetName()),
                        usedFileNames);
                Path csvPath = outputDirectory.resolve(fileName);

                exportSheet(
                        sheet,
                        csvPath,
                        separator,
                        formatter,
                        evaluator);
            }
        }
    }

    private static void exportSheet(
            final Sheet sheet,
            final Path csvPath,
            final char separator,
            final DataFormatter formatter,
            final FormulaEvaluator evaluator) throws IOException {

        int columnCount = maximumColumnCount(sheet);
        int rowCount = sheet.getPhysicalNumberOfRows() == 0
                ? 0
                : sheet.getLastRowNum() + 1;

        try (BufferedWriter writer = Files.newBufferedWriter(
                csvPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {

            for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                writeCsvRow(
                        writer,
                        row,
                        columnCount,
                        separator,
                        formatter,
                        evaluator);
            }
        }

        LOGGER.log(
                Level.INFO,
                "Foglio {0} esportato in {1}: righe={2}, colonne={3}",
                new Object[]{
                        sheet.getSheetName(),
                        csvPath.getFileName(),
                        rowCount,
                        columnCount
                });
    }

    private static void writeCsvRow(
            final BufferedWriter writer,
            final Row row,
            final int columnCount,
            final char separator,
            final DataFormatter formatter,
            final FormulaEvaluator evaluator) throws IOException {

        StringBuilder line = new StringBuilder();

        for (int columnIndex = 0;
                columnIndex < columnCount;
                columnIndex++) {

            if (columnIndex > 0) {
                line.append(separator);
            }

            Cell cell = row == null
                    ? null
                    : row.getCell(
                            columnIndex,
                            Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);

            String value = cell == null
                    ? ""
                    : formatter.formatCellValue(cell, evaluator);

            line.append(escapeCsv(value, separator));
        }

        writer.write(line.toString());
        writer.newLine();
    }

    private static int maximumColumnCount(final Sheet sheet) {
        int maximum = 0;
        for (Row row : sheet) {
            if (row != null && row.getLastCellNum() > maximum) {
                maximum = row.getLastCellNum();
            }
        }
        return maximum;
    }

    private static String escapeCsv(
            final String rawValue,
            final char separator) {

        String value = rawValue == null ? "" : rawValue;
        boolean requiresQuotes =
                value.indexOf(separator) >= 0
                        || value.indexOf('"') >= 0
                        || value.indexOf('\n') >= 0
                        || value.indexOf('\r') >= 0
                        || !value.equals(value.trim());

        if (!requiresQuotes) {
            return value;
        }

        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String csvFileName(final String sheetName) {
        if ("Dataset".equalsIgnoreCase(sheetName)) {
            return "A.csv";
        }
        if ("Tickets".equalsIgnoreCase(sheetName)) {
            return "Tickets.csv";
        }

        String sanitized = sheetName
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .trim();

        if (sanitized.isBlank()) {
            sanitized = "Sheet";
        }
        return sanitized + ".csv";
    }

    private static String uniqueFileName(
            final String requestedName,
            final Set<String> usedFileNames) {

        String candidate = requestedName;
        int suffix = 2;

        while (!usedFileNames.add(candidate.toLowerCase(Locale.ROOT))) {
            int extensionIndex = requestedName.lastIndexOf('.');
            String base = extensionIndex < 0
                    ? requestedName
                    : requestedName.substring(0, extensionIndex);
            String extension = extensionIndex < 0
                    ? ""
                    : requestedName.substring(extensionIndex);
            candidate = base + "_" + suffix++ + extension;
        }

        return candidate;
    }

    private static Path resolveWorkbookPath() {
        String configuredWorkbook =
                System.getProperty("milestone.workbook");

        if (configuredWorkbook != null
                && !configuredWorkbook.isBlank()) {
            Path path = Paths.get(configuredWorkbook)
                    .toAbsolutePath()
                    .normalize();
            assertWorkbookExists(path);
            return path;
        }

        String configuredRoot =
                System.getProperty("milestone.root");

        if (configuredRoot != null
                && !configuredRoot.isBlank()) {
            Path path = Paths.get(configuredRoot)
                    .toAbsolutePath()
                    .normalize()
                    .resolve("output")
                    .resolve(WORKBOOK_FILE_NAME);
            assertWorkbookExists(path);
            return path;
        }

        Path current = Paths.get("")
                .toAbsolutePath()
                .normalize();

        while (current != null) {
            Path directCandidate = current
                    .resolve("output")
                    .resolve(WORKBOOK_FILE_NAME);
            if (Files.isRegularFile(directCandidate)) {
                return directCandidate;
            }

            Path moduleCandidate = current
                    .resolve("milestone-1")
                    .resolve("output")
                    .resolve(WORKBOOK_FILE_NAME);
            if (Files.isRegularFile(moduleCandidate)) {
                return moduleCandidate;
            }

            current = current.getParent();
        }

        throw new IllegalStateException(
                "Workbook non trovato. Atteso un file "
                        + WORKBOOK_FILE_NAME
                        + " nella cartella output del modulo milestone-1. "
                        + "In alternativa specificare "
                        + "-Dmilestone.workbook=<percorso completo> "
                        + "oppure -Dmilestone.root=<cartella milestone-1>.");
    }

    private static Path resolveOutputDirectory(
            final Path workbookPath) {

        String configured =
                System.getProperty("milestone.csvDir");

        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured)
                    .toAbsolutePath()
                    .normalize();
        }

        return workbookPath.getParent();
    }

    private static char resolveSeparator() {
        String configured = System.getProperty(
                "milestone.csvSeparator",
                ",");

        if (configured == null || configured.isEmpty()) {
            return ',';
        }

        if ("semicolon".equalsIgnoreCase(configured)) {
            return ';';
        }

        if ("tab".equalsIgnoreCase(configured)) {
            return '\t';
        }

        return configured.charAt(0);
    }

    private static void assertWorkbookExists(final Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(
                    "Workbook non trovato: " + path);
        }
    }
}
