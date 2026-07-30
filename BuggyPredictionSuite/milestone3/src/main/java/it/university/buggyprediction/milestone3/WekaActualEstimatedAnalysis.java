package it.university.buggyprediction.milestone3;

import javax.imageio.ImageIO;
import javax.swing.JOptionPane;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Legge gli output testuali prodotti da WEKA e ricava Actual ed Estimated
 * dalla sezione "=== Confusion Matrix ===".
 *
 * Actual    = numero di istanze realmente appartenenti alla classe positiva Yes.
 * Estimated = numero di istanze predette da WEKA come classe positiva Yes.
 *
 * Non richiede WEKA come dipendenza: analizza soltanto i file .txt esportati.
 */
public final class WekaActualEstimatedAnalysis {

    private static final Pattern MATRIX_ROW = Pattern.compile(
            "^\\s*((?:\\d+\\s+)+)\\|\\s*([A-Za-z])\\s*=\\s*(.+?)\\s*$"
    );

    private static final Pattern TOTAL_INSTANCES = Pattern.compile(
            "^\\s*Total Number of Instances\\s+(\\d+)\\s*$"
    );

    private static final Pattern CORRECTLY_CLASSIFIED = Pattern.compile(
            "^\\s*Correctly Classified Instances\\s+(\\d+)\\s+([0-9.,]+)\\s*%\\s*$"
    );

    private static final DecimalFormat DECIMAL;

    static {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.US);
        DECIMAL = new DecimalFormat("0.00", symbols);
    }

    private WekaActualEstimatedAnalysis() {
    }

    public enum DatasetId {
        A("A", "Dataset A",
                "AResult.txt", "A_Result.txt", "ResultA.txt"),
        B_PLUS("B+", "Dataset B+",
                "B+Result.txt", "BPlusResult.txt", "B_PlusResult.txt",
                "B+_Result.txt", "B_plus_Result.txt"),
        B("B", "Dataset B",
                "BResult.txt", "B_Result.txt", "ResultB.txt"),
        C("C", "Dataset C",
                "CResult.txt", "C_Result.txt", "ResultC.txt");

        private final String shortName;
        private final String displayName;
        private final List<String> acceptedFileNames;

        DatasetId(String shortName, String displayName, String... acceptedFileNames) {
            this.shortName = shortName;
            this.displayName = displayName;
            this.acceptedFileNames = Arrays.asList(acceptedFileNames);
        }

        public String shortName() {
            return shortName;
        }

        public String displayName() {
            return displayName;
        }

        public List<String> acceptedFileNames() {
            return acceptedFileNames;
        }
    }

    public static void generateAll() throws IOException {
        Path milestone3Root = findMilestone3Root();
        Path wekaDirectory = findWekaDirectory(milestone3Root);
        Path outputDirectory = milestone3Root.resolve("output").resolve("actual-estimated");
        Files.createDirectories(outputDirectory);

        Map<DatasetId, WekaResult> results = new EnumMap<>(DatasetId.class);
        for (DatasetId datasetId : DatasetId.values()) {
            Path input = locateResultFile(wekaDirectory, datasetId);
            results.put(datasetId, parse(input, datasetId));
        }

        writePaperTableCsv(outputDirectory.resolve("actual_estimated_paper_table.csv"), results);
        writeDetailedCsv(outputDirectory.resolve("actual_estimated_details.csv"), results);
        writeReport(outputDirectory.resolve("actual_estimated_report.txt"), wekaDirectory, results);
        drawPaperTable(outputDirectory.resolve("actual_estimated_paper_table.png"), results);
        drawCombinedChart(outputDirectory.resolve("actual_estimated_chart.png"), results);
        writeHtml(outputDirectory.resolve("actual_estimated_paper_table.html"), results);

        for (DatasetId datasetId : DatasetId.values()) {
            drawSingleChart(
                    outputDirectory.resolve("dataset_" + safeName(datasetId) + "_actual_estimated.png"),
                    results.get(datasetId),
                    datasetId == DatasetId.B ? results.get(DatasetId.B_PLUS) : null
            );
        }

        String message = "Analisi Actual/Estimated completata.\n\n"
                + summaryForDialog(results)
                + "\nOutput:\n" + outputDirectory.toAbsolutePath();
        showInfo(message);
    }

    public static void generateSingle(DatasetId datasetId) throws IOException {
        Path milestone3Root = findMilestone3Root();
        Path wekaDirectory = findWekaDirectory(milestone3Root);
        Path outputDirectory = milestone3Root.resolve("output").resolve("actual-estimated");
        Files.createDirectories(outputDirectory);

        Path input = locateResultFile(wekaDirectory, datasetId);
        WekaResult result = parse(input, datasetId);

        WekaResult bPlusReference = null;
        if (datasetId == DatasetId.B) {
            try {
                bPlusReference = parse(
                        locateResultFile(wekaDirectory, DatasetId.B_PLUS),
                        DatasetId.B_PLUS
                );
            } catch (Exception ignored) {
                // Il grafico B funziona anche senza il riferimento B+.
            }
        }

        drawSingleChart(
                outputDirectory.resolve("dataset_" + safeName(datasetId) + "_actual_estimated.png"),
                result,
                bPlusReference
        );
        writeSingleCsv(
                outputDirectory.resolve("dataset_" + safeName(datasetId) + "_actual_estimated.csv"),
                result
        );
        writeSingleReport(
                outputDirectory.resolve("dataset_" + safeName(datasetId) + "_actual_estimated.txt"),
                result
        );

        String actualText = datasetId == DatasetId.B
                ? "non riportato nella tabella What-If"
                : Long.toString(result.actualPositive);

        showInfo(
                result.datasetId.displayName() + " analizzato correttamente.\n\n"
                        + "Actual: " + actualText + "\n"
                        + "Estimated: " + result.estimatedPositive + "\n"
                        + "File WEKA: " + result.sourceFile.toAbsolutePath() + "\n\n"
                        + "Output: " + outputDirectory.toAbsolutePath()
        );
    }

    public static void runSafely(CheckedRunnable runnable) {
        try {
            runnable.run();
        } catch (Exception exception) {
            exception.printStackTrace();
            showError(
                    "Impossibile completare l'analisi Actual/Estimated.\n\n"
                            + exception.getClass().getSimpleName() + ": "
                            + exception.getMessage()
            );
        }
    }

    private static WekaResult parse(Path input, DatasetId datasetId) throws IOException {
        List<String> lines = Files.readAllLines(input, StandardCharsets.UTF_8);

        int matrixMarker = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equalsIgnoreCase("=== Confusion Matrix ===")) {
                matrixMarker = i;
            }
        }

        if (matrixMarker < 0) {
            throw new IOException(
                    "Nel file " + input.getFileName()
                            + " non è presente la sezione '=== Confusion Matrix ==='."
            );
        }

        List<MatrixRow> rows = new ArrayList<>();
        for (int i = matrixMarker + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher matcher = MATRIX_ROW.matcher(line);
            if (matcher.matches()) {
                String[] numberTokens = matcher.group(1).trim().split("\\s+");
                long[] counts = new long[numberTokens.length];
                for (int j = 0; j < numberTokens.length; j++) {
                    counts[j] = Long.parseLong(numberTokens[j]);
                }
                rows.add(new MatrixRow(
                        matcher.group(2).charAt(0),
                        matcher.group(3).trim(),
                        counts
                ));
            } else if (!rows.isEmpty() && line.trim().isEmpty()) {
                break;
            }
        }

        if (rows.size() < 2) {
            throw new IOException(
                    "La confusion matrix in " + input.getFileName()
                            + " non contiene almeno due classi."
            );
        }

        int classCount = rows.size();
        for (MatrixRow row : rows) {
            if (row.counts.length != classCount) {
                throw new IOException(
                        "La confusion matrix in " + input.getFileName()
                                + " non è quadrata: trovate " + classCount
                                + " righe ma " + row.counts.length + " colonne."
                );
            }
        }

        int positiveIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (isPositiveLabel(rows.get(i).classLabel)) {
                positiveIndex = i;
                break;
            }
        }

        if (positiveIndex < 0) {
            throw new IOException(
                    "Non è stata trovata la classe positiva 'Yes' nella confusion matrix di "
                            + input.getFileName() + ". Classi trovate: "
                            + rows.stream()
                            .map(row -> row.classLabel)
                            .collect(Collectors.joining(", "))
            );
        }

        long totalFromMatrix = 0;
        for (MatrixRow row : rows) {
            for (long value : row.counts) {
                totalFromMatrix += value;
            }
        }

        long actualPositive = sum(rows.get(positiveIndex).counts);
        long estimatedPositive = 0;
        for (MatrixRow row : rows) {
            estimatedPositive += row.counts[positiveIndex];
        }

        long truePositive = rows.get(positiveIndex).counts[positiveIndex];
        long falseNegative = actualPositive - truePositive;
        long falsePositive = estimatedPositive - truePositive;
        long trueNegative = totalFromMatrix - truePositive - falseNegative - falsePositive;

        Long declaredTotal = null;
        Long correctlyClassified = null;
        Double accuracyPercent = null;

        for (String line : lines) {
            Matcher totalMatcher = TOTAL_INSTANCES.matcher(line);
            if (totalMatcher.matches()) {
                declaredTotal = Long.parseLong(totalMatcher.group(1));
            }

            Matcher correctMatcher = CORRECTLY_CLASSIFIED.matcher(line);
            if (correctMatcher.matches()) {
                correctlyClassified = Long.parseLong(correctMatcher.group(1));
                accuracyPercent = parseWekaDecimal(correctMatcher.group(2));
            }
        }

        if (declaredTotal != null && declaredTotal != totalFromMatrix) {
            throw new IOException(
                    "Il totale dichiarato da WEKA (" + declaredTotal
                            + ") non coincide con il totale della confusion matrix ("
                            + totalFromMatrix + ") in " + input.getFileName() + "."
            );
        }

        return new WekaResult(
                datasetId,
                input,
                rows,
                positiveIndex,
                actualPositive,
                estimatedPositive,
                truePositive,
                falseNegative,
                falsePositive,
                trueNegative,
                totalFromMatrix,
                correctlyClassified,
                accuracyPercent
        );
    }

    private static Path findMilestone3Root() throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();

        Path cursor = current;
        for (int level = 0; level < 10 && cursor != null; level++) {
            if (isMilestone3Directory(cursor)) {
                return cursor;
            }

            Path child = cursor.resolve("milestone3");
            if (isMilestone3Directory(child)) {
                return child;
            }

            Path nested = cursor.resolve("BuggyPredictionSuite").resolve("milestone3");
            if (isMilestone3Directory(nested)) {
                return nested;
            }

            cursor = cursor.getParent();
        }

        throw new IOException(
                "Non trovo la cartella milestone3 partendo dalla working directory di IntelliJ: "
                        + current
        );
    }

    private static boolean isMilestone3Directory(Path path) {
        return path != null
                && Files.isDirectory(path)
                && path.getFileName() != null
                && path.getFileName().toString().equalsIgnoreCase("milestone3")
                && Files.isDirectory(path.resolve("output"));
    }

    private static Path findWekaDirectory(Path milestone3Root) throws IOException {
        List<Path> candidates = Arrays.asList(
                milestone3Root.resolve("output").resolve("wekaTests"),
                milestone3Root.resolve("output").resolve("wekaResults"),
                milestone3Root.resolve("output").resolve("wekatests"),
                milestone3Root.resolve("output").resolve("wekaresults")
        );

        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }

        Path output = milestone3Root.resolve("output");
        if (Files.isDirectory(output)) {
            try (Stream<Path> stream = Files.walk(output, 2)) {
                boolean hasResultFiles = stream.anyMatch(path ->
                        Files.isRegularFile(path)
                                && path.getFileName().toString()
                                .toLowerCase(Locale.ROOT).endsWith("result.txt")
                );
                if (hasResultFiles) {
                    return output;
                }
            }
        }

        throw new IOException(
                "Non trovo la directory dei risultati WEKA. Percorsi attesi:\n"
                        + milestone3Root.resolve("output").resolve("wekaTests") + "\n"
                        + milestone3Root.resolve("output").resolve("wekaResults")
        );
    }

    private static Path locateResultFile(Path wekaDirectory, DatasetId datasetId)
            throws IOException {

        List<Path> txtFiles;
        try (Stream<Path> stream = Files.walk(wekaDirectory)) {
            txtFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(".txt"))
                    .sorted(Comparator.comparing(path ->
                            path.toAbsolutePath().toString().toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }

        for (String accepted : datasetId.acceptedFileNames()) {
            String normalizedAccepted = normalizeFileName(accepted);
            for (Path candidate : txtFiles) {
                if (normalizeFileName(candidate.getFileName().toString())
                        .equals(normalizedAccepted)) {
                    return candidate;
                }
            }
        }

        throw new IOException(
                "Non trovo il file WEKA per " + datasetId.displayName()
                        + " dentro " + wekaDirectory.toAbsolutePath()
                        + ". Nomi accettati: "
                        + String.join(", ", datasetId.acceptedFileNames())
        );
    }

    private static String normalizeFileName(String fileName) {
        String normalized = fileName.toLowerCase(Locale.ROOT)
                .replace("+", "plus")
                .replace(".txt", "");
        return normalized.replaceAll("[^a-z0-9]", "");
    }

    private static boolean isPositiveLabel(String label) {
        String normalized = label.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("yes")
                || normalized.equals("buggy")
                || normalized.equals("true")
                || normalized.equals("positive")
                || normalized.equals("1");
    }

    private static long sum(long[] values) {
        long total = 0;
        for (long value : values) {
            total += value;
        }
        return total;
    }

    private static double parseWekaDecimal(String value) {
        return Double.parseDouble(value.replace(',', '.'));
    }

    private static void writePaperTableCsv(
            Path output,
            Map<DatasetId, WekaResult> results
    ) throws IOException {

        WekaResult a = results.get(DatasetId.A);
        WekaResult bPlus = results.get(DatasetId.B_PLUS);
        WekaResult b = results.get(DatasetId.B);
        WekaResult c = results.get(DatasetId.C);

        try (BufferedWriter writer = Files.newBufferedWriter(
                output, StandardCharsets.UTF_8)) {

            writer.write("Dataset A,,Dataset B+,,Dataset B,Dataset C,");
            writer.newLine();
            writer.write("Actual,Estimated,Actual,Estimated,Estimated,Actual,Estimated");
            writer.newLine();
            writer.write(
                    a.actualPositive + "," + a.estimatedPositive + ","
                            + bPlus.actualPositive + "," + bPlus.estimatedPositive + ","
                            + b.estimatedPositive + ","
                            + c.actualPositive + "," + c.estimatedPositive
            );
            writer.newLine();
        }
    }

    private static void writeDetailedCsv(
            Path output,
            Map<DatasetId, WekaResult> results
    ) throws IOException {

        try (BufferedWriter writer = Files.newBufferedWriter(
                output, StandardCharsets.UTF_8)) {

            writer.write(
                    "Dataset,ActualYes,EstimatedYes,TP,FN,FP,TN,Total,"
                            + "EstimatedMinusActual,AbsoluteRelativeErrorPercent,"
                            + "CorrectlyClassified,AccuracyPercent,SourceFile"
            );
            writer.newLine();

            for (DatasetId datasetId : DatasetId.values()) {
                WekaResult result = results.get(datasetId);
                String actualForPaper = datasetId == DatasetId.B
                        ? ""
                        : Long.toString(result.actualPositive);
                String difference = datasetId == DatasetId.B
                        ? ""
                        : Long.toString(result.estimatedPositive - result.actualPositive);
                String relativeError = datasetId == DatasetId.B
                        ? ""
                        : formatPercent(relativeError(
                                result.actualPositive,
                                result.estimatedPositive
                        ));

                writer.write(csv(datasetId.shortName()));
                writer.write(",");
                writer.write(actualForPaper);
                writer.write(",");
                writer.write(Long.toString(result.estimatedPositive));
                writer.write(",");
                writer.write(Long.toString(result.truePositive));
                writer.write(",");
                writer.write(Long.toString(result.falseNegative));
                writer.write(",");
                writer.write(Long.toString(result.falsePositive));
                writer.write(",");
                writer.write(Long.toString(result.trueNegative));
                writer.write(",");
                writer.write(Long.toString(result.total));
                writer.write(",");
                writer.write(difference);
                writer.write(",");
                writer.write(relativeError);
                writer.write(",");
                writer.write(result.correctlyClassified == null
                        ? "" : result.correctlyClassified.toString());
                writer.write(",");
                writer.write(result.accuracyPercent == null
                        ? "" : DECIMAL.format(result.accuracyPercent));
                writer.write(",");
                writer.write(csv(result.sourceFile.toAbsolutePath().toString()));
                writer.newLine();
            }
        }
    }

    private static void writeSingleCsv(Path output, WekaResult result)
            throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(
                output, StandardCharsets.UTF_8)) {

            writer.write("Dataset,ActualYes,EstimatedYes,TP,FN,FP,TN,Total,SourceFile");
            writer.newLine();
            writer.write(csv(result.datasetId.shortName()));
            writer.write(",");
            writer.write(result.datasetId == DatasetId.B
                    ? "" : Long.toString(result.actualPositive));
            writer.write(",");
            writer.write(Long.toString(result.estimatedPositive));
            writer.write(",");
            writer.write(Long.toString(result.truePositive));
            writer.write(",");
            writer.write(Long.toString(result.falseNegative));
            writer.write(",");
            writer.write(Long.toString(result.falsePositive));
            writer.write(",");
            writer.write(Long.toString(result.trueNegative));
            writer.write(",");
            writer.write(Long.toString(result.total));
            writer.write(",");
            writer.write(csv(result.sourceFile.toAbsolutePath().toString()));
            writer.newLine();
        }
    }

    private static void writeReport(
            Path output,
            Path wekaDirectory,
            Map<DatasetId, WekaResult> results
    ) throws IOException {

        WekaResult a = results.get(DatasetId.A);
        WekaResult bPlus = results.get(DatasetId.B_PLUS);
        WekaResult b = results.get(DatasetId.B);
        WekaResult c = results.get(DatasetId.C);

        try (BufferedWriter writer = Files.newBufferedWriter(
                output, StandardCharsets.UTF_8)) {

            writer.write("ANALISI ACTUAL / ESTIMATED - MILESTONE 3");
            writer.newLine();
            writer.write("=======================================");
            writer.newLine();
            writer.newLine();

            writer.write("Directory risultati WEKA: "
                    + wekaDirectory.toAbsolutePath());
            writer.newLine();
            writer.newLine();

            writer.write("Metodo di calcolo");
            writer.newLine();
            writer.write("-----------------");
            writer.newLine();
            writer.write(
                    "La classe positiva è Yes. Dalla confusion matrix WEKA:"
            );
            writer.newLine();
            writer.write(
                    "- Actual = somma della riga della classe reale Yes (TP + FN)."
            );
            writer.newLine();
            writer.write(
                    "- Estimated = somma della colonna predetta Yes (TP + FP)."
            );
            writer.newLine();
            writer.write(
                    "- Per il dataset B la tabella What-If riporta soltanto Estimated."
            );
            writer.newLine();
            writer.newLine();

            for (DatasetId datasetId : DatasetId.values()) {
                WekaResult result = results.get(datasetId);
                writer.write(result.datasetId.displayName());
                writer.newLine();
                writer.write("  File: " + result.sourceFile.toAbsolutePath());
                writer.newLine();
                writer.write("  Matrice: TP=" + result.truePositive
                        + ", FN=" + result.falseNegative
                        + ", FP=" + result.falsePositive
                        + ", TN=" + result.trueNegative);
                writer.newLine();
                if (datasetId != DatasetId.B) {
                    writer.write("  Actual Yes: " + result.actualPositive);
                    writer.newLine();
                } else {
                    writer.write(
                            "  Actual Yes nel file: " + result.actualPositive
                                    + " (non riportato nella tabella sintetica del paper)"
                    );
                    writer.newLine();
                }
                writer.write("  Estimated Yes: " + result.estimatedPositive);
                writer.newLine();
                if (datasetId != DatasetId.B) {
                    writer.write("  Estimated - Actual: "
                            + (result.estimatedPositive - result.actualPositive));
                    writer.newLine();
                    writer.write("  Errore relativo assoluto: "
                            + formatPercent(relativeError(
                            result.actualPositive,
                            result.estimatedPositive
                    )) + "%");
                    writer.newLine();
                }
                writer.newLine();
            }

            long whatIfDifference = b.estimatedPositive - bPlus.actualPositive;
            double whatIfPercent = bPlus.actualPositive == 0
                    ? Double.NaN
                    : 100.0 * whatIfDifference / bPlus.actualPositive;

            long estimatedChange = b.estimatedPositive - bPlus.estimatedPositive;
            double estimatedChangePercent = bPlus.estimatedPositive == 0
                    ? Double.NaN
                    : 100.0 * estimatedChange / bPlus.estimatedPositive;

            writer.write("What-If scenario");
            writer.newLine();
            writer.write("----------------");
            writer.newLine();
            writer.write("Actual B+: " + bPlus.actualPositive);
            writer.newLine();
            writer.write("Estimated B+: " + bPlus.estimatedPositive);
            writer.newLine();
            writer.write("Estimated B: " + b.estimatedPositive);
            writer.newLine();
            writer.write(
                    "Estimated B - Actual B+: " + whatIfDifference
                            + " (" + signedPercent(whatIfPercent) + "%)"
            );
            writer.newLine();
            writer.write(
                    "Estimated B - Estimated B+: " + estimatedChange
                            + " (" + signedPercent(estimatedChangePercent) + "%)"
            );
            writer.newLine();
            writer.newLine();

            writer.write("Tabella compatta");
            writer.newLine();
            writer.write("----------------");
            writer.newLine();
            writer.write(
                    "A: Actual=" + a.actualPositive
                            + ", Estimated=" + a.estimatedPositive
            );
            writer.newLine();
            writer.write(
                    "B+: Actual=" + bPlus.actualPositive
                            + ", Estimated=" + bPlus.estimatedPositive
            );
            writer.newLine();
            writer.write("B: Estimated=" + b.estimatedPositive);
            writer.newLine();
            writer.write(
                    "C: Actual=" + c.actualPositive
                            + ", Estimated=" + c.estimatedPositive
            );
            writer.newLine();
        }
    }

    private static void writeSingleReport(Path output, WekaResult result)
            throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(
                output, StandardCharsets.UTF_8)) {
            writer.write(result.datasetId.displayName());
            writer.newLine();
            writer.write("================");
            writer.newLine();
            writer.write("File: " + result.sourceFile.toAbsolutePath());
            writer.newLine();
            if (result.datasetId != DatasetId.B) {
                writer.write("Actual Yes: " + result.actualPositive);
                writer.newLine();
            } else {
                writer.write(
                        "Actual Yes nel file: " + result.actualPositive
                                + " (non riportato nella tabella What-If)"
                );
                writer.newLine();
            }
            writer.write("Estimated Yes: " + result.estimatedPositive);
            writer.newLine();
            writer.write("TP=" + result.truePositive
                    + ", FN=" + result.falseNegative
                    + ", FP=" + result.falsePositive
                    + ", TN=" + result.trueNegative);
            writer.newLine();
            writer.write("Totale: " + result.total);
            writer.newLine();
        }
    }

    private static void writeHtml(
            Path output,
            Map<DatasetId, WekaResult> results
    ) throws IOException {

        WekaResult a = results.get(DatasetId.A);
        WekaResult bPlus = results.get(DatasetId.B_PLUS);
        WekaResult b = results.get(DatasetId.B);
        WekaResult c = results.get(DatasetId.C);

        String html = "<!doctype html>\n"
                + "<html lang=\"it\"><head><meta charset=\"UTF-8\">"
                + "<title>Actual ed Estimated</title>"
                + "<style>"
                + "body{font-family:Arial,sans-serif;margin:40px;background:#fff;color:#111}"
                + "h1{text-align:center;font-family:Georgia,serif}"
                + "table{border-collapse:collapse;margin:40px auto;font-size:20px}"
                + "th,td{padding:10px 18px;text-align:center;border-bottom:1px solid #222}"
                + "thead tr:first-child th{font-size:23px;border-top:2px solid #222}"
                + "tbody tr:last-child td{border-bottom:2px solid #222}"
                + "</style></head><body>"
                + "<h1>Valori Actual ed Estimated per i dataset di valutazione</h1>"
                + "<table><thead>"
                + "<tr><th colspan=\"2\">Dataset A</th>"
                + "<th colspan=\"2\">Dataset B+</th>"
                + "<th>Dataset B</th>"
                + "<th colspan=\"2\">Dataset C</th></tr>"
                + "<tr><th>Actual</th><th>Estimated</th>"
                + "<th>Actual</th><th>Estimated</th>"
                + "<th>Estimated</th>"
                + "<th>Actual</th><th>Estimated</th></tr>"
                + "</thead><tbody><tr>"
                + "<td>" + a.actualPositive + "</td>"
                + "<td>" + a.estimatedPositive + "</td>"
                + "<td>" + bPlus.actualPositive + "</td>"
                + "<td>" + bPlus.estimatedPositive + "</td>"
                + "<td>" + b.estimatedPositive + "</td>"
                + "<td>" + c.actualPositive + "</td>"
                + "<td>" + c.estimatedPositive + "</td>"
                + "</tr></tbody></table></body></html>";

        Files.write(output, html.getBytes(StandardCharsets.UTF_8));
    }

    private static void drawPaperTable(
            Path output,
            Map<DatasetId, WekaResult> results
    ) throws IOException {

        WekaResult a = results.get(DatasetId.A);
        WekaResult bPlus = results.get(DatasetId.B_PLUS);
        WekaResult b = results.get(DatasetId.B);
        WekaResult c = results.get(DatasetId.C);

        int width = 1500;
        int height = 430;
        BufferedImage image = new BufferedImage(
                width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        prepareGraphics(graphics);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);

        graphics.setColor(new Color(20, 20, 20));
        graphics.setFont(new Font("Serif", Font.PLAIN, 34));
        drawCentered(
                graphics,
                "Valori Actual ed Estimated per i dataset di valutazione",
                width / 2,
                62
        );

        int left = 95;
        int right = width - 95;
        int top = 118;
        int groupHeaderY = 165;
        int subHeaderY = 235;
        int valueY = 315;
        int bottom = 352;

        graphics.setStroke(new BasicStroke(2f));
        graphics.drawLine(left, top, right, top);
        graphics.drawLine(left, 196, right, 196);
        graphics.drawLine(left, 265, right, 265);
        graphics.drawLine(left, bottom, right, bottom);

        int[] widths = {180, 180, 180, 180, 220, 180, 180};
        int[] centers = new int[widths.length];
        int cursor = left;
        for (int i = 0; i < widths.length; i++) {
            centers[i] = cursor + widths[i] / 2;
            cursor += widths[i];
        }

        graphics.setFont(new Font("Serif", Font.BOLD, 34));
        drawCentered(graphics, "Dataset A",
                (centers[0] + centers[1]) / 2, groupHeaderY);
        drawCentered(graphics, "Dataset B+",
                (centers[2] + centers[3]) / 2, groupHeaderY);
        drawCentered(graphics, "Dataset B", centers[4], groupHeaderY);
        drawCentered(graphics, "Dataset C",
                (centers[5] + centers[6]) / 2, groupHeaderY);

        graphics.setFont(new Font("Serif", Font.BOLD, 29));
        String[] subHeaders = {
                "Actual", "Estimated", "Actual", "Estimated",
                "Estimated", "Actual", "Estimated"
        };
        for (int i = 0; i < subHeaders.length; i++) {
            drawCentered(graphics, subHeaders[i], centers[i], subHeaderY);
        }

        graphics.setFont(new Font("Serif", Font.PLAIN, 34));
        long[] values = {
                a.actualPositive, a.estimatedPositive,
                bPlus.actualPositive, bPlus.estimatedPositive,
                b.estimatedPositive,
                c.actualPositive, c.estimatedPositive
        };
        for (int i = 0; i < values.length; i++) {
            drawCentered(graphics, Long.toString(values[i]), centers[i], valueY);
        }

        graphics.dispose();
        ImageIO.write(image, "png", output.toFile());
    }

    private static void drawCombinedChart(
            Path output,
            Map<DatasetId, WekaResult> results
    ) throws IOException {

        WekaResult a = results.get(DatasetId.A);
        WekaResult bPlus = results.get(DatasetId.B_PLUS);
        WekaResult b = results.get(DatasetId.B);
        WekaResult c = results.get(DatasetId.C);

        List<ChartGroup> groups = Arrays.asList(
                new ChartGroup("A", a.actualPositive, a.estimatedPositive, true),
                new ChartGroup("B+", bPlus.actualPositive,
                        bPlus.estimatedPositive, true),
                new ChartGroup("B", 0, b.estimatedPositive, false),
                new ChartGroup("C", c.actualPositive, c.estimatedPositive, true)
        );

        long max = 1;
        for (ChartGroup group : groups) {
            max = Math.max(max, Math.max(group.actual, group.estimated));
        }

        int width = 1500;
        int height = 850;
        int left = 120;
        int right = 70;
        int top = 120;
        int bottom = 135;
        int plotWidth = width - left - right;
        int plotHeight = height - top - bottom;

        BufferedImage image = new BufferedImage(
                width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        prepareGraphics(graphics);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);

        graphics.setColor(new Color(20, 20, 20));
        graphics.setFont(new Font("SansSerif", Font.BOLD, 35));
        drawCentered(
                graphics,
                "Actual ed Estimated – Milestone 3",
                width / 2,
                58
        );

        graphics.setFont(new Font("SansSerif", Font.PLAIN, 19));
        drawLegend(graphics, width - 410, 74);

        graphics.setColor(new Color(70, 70, 70));
        graphics.setStroke(new BasicStroke(2f));
        graphics.drawLine(left, top, left, top + plotHeight);
        graphics.drawLine(left, top + plotHeight,
                left + plotWidth, top + plotHeight);

        int ticks = 6;
        graphics.setFont(new Font("SansSerif", Font.PLAIN, 17));
        for (int i = 0; i <= ticks; i++) {
            long tickValue = Math.round((double) max * i / ticks);
            int y = top + plotHeight
                    - (int) Math.round((double) plotHeight * tickValue / max);
            graphics.setColor(new Color(225, 225, 225));
            graphics.setStroke(new BasicStroke(1f));
            graphics.drawLine(left, y, left + plotWidth, y);
            graphics.setColor(new Color(70, 70, 70));
            drawRightAligned(graphics, Long.toString(tickValue), left - 16, y + 6);
        }

        int groupWidth = plotWidth / groups.size();
        int barWidth = 95;

        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            ChartGroup group = groups.get(groupIndex);
            int centerX = left + groupIndex * groupWidth + groupWidth / 2;

            if (group.hasActual) {
                int actualX = centerX - barWidth - 12;
                drawBar(
                        graphics,
                        actualX,
                        top,
                        plotHeight,
                        barWidth,
                        group.actual,
                        max,
                        new Color(74, 114, 173)
                );
            }

            int estimatedX = group.hasActual
                    ? centerX + 12
                    : centerX - barWidth / 2;

            drawBar(
                    graphics,
                    estimatedX,
                    top,
                    plotHeight,
                    barWidth,
                    group.estimated,
                    max,
                    new Color(225, 119, 62)
            );

            graphics.setColor(new Color(30, 30, 30));
            graphics.setFont(new Font("SansSerif", Font.BOLD, 24));
            drawCentered(
                    graphics,
                    "Dataset " + group.label,
                    centerX,
                    top + plotHeight + 47
            );

            if (!group.hasActual) {
                graphics.setFont(new Font("SansSerif", Font.PLAIN, 17));
                drawCentered(
                        graphics,
                        "scenario sintetico",
                        centerX,
                        top + plotHeight + 78
                );
            }
        }

        graphics.dispose();
        ImageIO.write(image, "png", output.toFile());
    }

    private static void drawSingleChart(
            Path output,
            WekaResult result,
            WekaResult bPlusReference
    ) throws IOException {

        boolean showActual = result.datasetId != DatasetId.B;
        long max = Math.max(1, Math.max(
                result.actualPositive,
                result.estimatedPositive
        ));
        if (bPlusReference != null) {
            max = Math.max(max, bPlusReference.actualPositive);
        }

        int width = 900;
        int height = 700;
        int left = 115;
        int right = 75;
        int top = 105;
        int bottom = 120;
        int plotWidth = width - left - right;
        int plotHeight = height - top - bottom;

        BufferedImage image = new BufferedImage(
                width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        prepareGraphics(graphics);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);

        graphics.setColor(new Color(20, 20, 20));
        graphics.setFont(new Font("SansSerif", Font.BOLD, 31));
        drawCentered(
                graphics,
                result.datasetId.displayName() + " – Actual ed Estimated",
                width / 2,
                52
        );

        graphics.setColor(new Color(70, 70, 70));
        graphics.setStroke(new BasicStroke(2f));
        graphics.drawLine(left, top, left, top + plotHeight);
        graphics.drawLine(left, top + plotHeight,
                left + plotWidth, top + plotHeight);

        int ticks = 5;
        graphics.setFont(new Font("SansSerif", Font.PLAIN, 16));
        for (int i = 0; i <= ticks; i++) {
            long tickValue = Math.round((double) max * i / ticks);
            int y = top + plotHeight
                    - (int) Math.round((double) plotHeight * tickValue / max);
            graphics.setColor(new Color(230, 230, 230));
            graphics.drawLine(left, y, left + plotWidth, y);
            graphics.setColor(new Color(70, 70, 70));
            drawRightAligned(graphics, Long.toString(tickValue), left - 14, y + 5);
        }

        int barWidth = 150;
        if (showActual) {
            drawBar(
                    graphics,
                    width / 2 - barWidth - 25,
                    top,
                    plotHeight,
                    barWidth,
                    result.actualPositive,
                    max,
                    new Color(74, 114, 173)
            );
            drawBar(
                    graphics,
                    width / 2 + 25,
                    top,
                    plotHeight,
                    barWidth,
                    result.estimatedPositive,
                    max,
                    new Color(225, 119, 62)
            );

            graphics.setFont(new Font("SansSerif", Font.BOLD, 22));
            graphics.setColor(new Color(30, 30, 30));
            drawCentered(
                    graphics,
                    "Actual",
                    width / 2 - barWidth / 2 - 25,
                    top + plotHeight + 48
            );
            drawCentered(
                    graphics,
                    "Estimated",
                    width / 2 + barWidth / 2 + 25,
                    top + plotHeight + 48
            );
        } else {
            drawBar(
                    graphics,
                    width / 2 - barWidth / 2,
                    top,
                    plotHeight,
                    barWidth,
                    result.estimatedPositive,
                    max,
                    new Color(225, 119, 62)
            );

            graphics.setFont(new Font("SansSerif", Font.BOLD, 22));
            graphics.setColor(new Color(30, 30, 30));
            drawCentered(
                    graphics,
                    "Estimated B",
                    width / 2,
                    top + plotHeight + 48
            );

            if (bPlusReference != null) {
                int referenceY = top + plotHeight
                        - (int) Math.round(
                        (double) plotHeight
                                * bPlusReference.actualPositive / max
                );
                graphics.setColor(new Color(74, 114, 173));
                graphics.setStroke(new BasicStroke(
                        3f,
                        BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_BEVEL,
                        0,
                        new float[]{12f, 8f},
                        0
                ));
                graphics.drawLine(left, referenceY,
                        left + plotWidth, referenceY);
                graphics.setFont(new Font("SansSerif", Font.PLAIN, 18));
                graphics.drawString(
                        "Actual B+ = " + bPlusReference.actualPositive,
                        left + 15,
                        referenceY - 10
                );
            }
        }

        graphics.dispose();
        ImageIO.write(image, "png", output.toFile());
    }

    private static void drawBar(
            Graphics2D graphics,
            int x,
            int plotTop,
            int plotHeight,
            int width,
            long value,
            long max,
            Color color
    ) {
        int height = (int) Math.round((double) plotHeight * value / max);
        int y = plotTop + plotHeight - height;

        graphics.setColor(color);
        graphics.fillRoundRect(x, y, width, height, 18, 18);
        graphics.setColor(color.darker());
        graphics.setStroke(new BasicStroke(1.5f));
        graphics.drawRoundRect(x, y, width, height, 18, 18);

        graphics.setColor(new Color(30, 30, 30));
        graphics.setFont(new Font("SansSerif", Font.BOLD, 20));
        drawCentered(graphics, Long.toString(value), x + width / 2, y - 12);
    }

    private static void drawLegend(Graphics2D graphics, int x, int y) {
        graphics.setFont(new Font("SansSerif", Font.PLAIN, 18));

        graphics.setColor(new Color(74, 114, 173));
        graphics.fillRect(x, y, 26, 18);
        graphics.setColor(new Color(30, 30, 30));
        graphics.drawString("Actual Yes", x + 36, y + 16);

        graphics.setColor(new Color(225, 119, 62));
        graphics.fillRect(x + 170, y, 26, 18);
        graphics.setColor(new Color(30, 30, 30));
        graphics.drawString("Estimated Yes", x + 206, y + 16);
    }

    private static void prepareGraphics(Graphics2D graphics) {
        graphics.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
        );
        graphics.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON
        );
    }

    private static void drawCentered(
            Graphics2D graphics,
            String text,
            int centerX,
            int baselineY
    ) {
        FontMetrics metrics = graphics.getFontMetrics();
        graphics.drawString(
                text,
                centerX - metrics.stringWidth(text) / 2,
                baselineY
        );
    }

    private static void drawRightAligned(
            Graphics2D graphics,
            String text,
            int rightX,
            int baselineY
    ) {
        FontMetrics metrics = graphics.getFontMetrics();
        graphics.drawString(
                text,
                rightX - metrics.stringWidth(text),
                baselineY
        );
    }

    private static double relativeError(long actual, long estimated) {
        if (actual == 0) {
            return Double.NaN;
        }
        return 100.0 * Math.abs(estimated - actual) / actual;
    }

    private static String formatPercent(double value) {
        return Double.isNaN(value) ? "NaN" : DECIMAL.format(value);
    }

    private static String signedPercent(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        return (value > 0 ? "+" : "") + DECIMAL.format(value);
    }

    private static String summaryForDialog(Map<DatasetId, WekaResult> results) {
        WekaResult a = results.get(DatasetId.A);
        WekaResult bPlus = results.get(DatasetId.B_PLUS);
        WekaResult b = results.get(DatasetId.B);
        WekaResult c = results.get(DatasetId.C);

        return "A: Actual=" + a.actualPositive
                + ", Estimated=" + a.estimatedPositive + "\n"
                + "B+: Actual=" + bPlus.actualPositive
                + ", Estimated=" + bPlus.estimatedPositive + "\n"
                + "B: Estimated=" + b.estimatedPositive + "\n"
                + "C: Actual=" + c.actualPositive
                + ", Estimated=" + c.estimatedPositive + "\n";
    }

    private static String safeName(DatasetId datasetId) {
        return datasetId == DatasetId.B_PLUS
                ? "B_plus"
                : datasetId.shortName();
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static void showInfo(String message) {
        System.out.println(message);
        if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(
                    null,
                    message,
                    "Milestone 3 - Actual/Estimated",
                    JOptionPane.INFORMATION_MESSAGE
            );
        }
    }

    private static void showError(String message) {
        System.err.println(message);
        if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(
                    null,
                    message,
                    "Errore Milestone 3",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws Exception;
    }

    private static final class MatrixRow {
        private final char classCode;
        private final String classLabel;
        private final long[] counts;

        private MatrixRow(char classCode, String classLabel, long[] counts) {
            this.classCode = classCode;
            this.classLabel = classLabel;
            this.counts = counts;
        }
    }

    public static final class WekaResult {
        private final DatasetId datasetId;
        private final Path sourceFile;
        private final List<MatrixRow> rows;
        private final int positiveIndex;
        private final long actualPositive;
        private final long estimatedPositive;
        private final long truePositive;
        private final long falseNegative;
        private final long falsePositive;
        private final long trueNegative;
        private final long total;
        private final Long correctlyClassified;
        private final Double accuracyPercent;

        private WekaResult(
                DatasetId datasetId,
                Path sourceFile,
                List<MatrixRow> rows,
                int positiveIndex,
                long actualPositive,
                long estimatedPositive,
                long truePositive,
                long falseNegative,
                long falsePositive,
                long trueNegative,
                long total,
                Long correctlyClassified,
                Double accuracyPercent
        ) {
            this.datasetId = datasetId;
            this.sourceFile = sourceFile;
            this.rows = rows;
            this.positiveIndex = positiveIndex;
            this.actualPositive = actualPositive;
            this.estimatedPositive = estimatedPositive;
            this.truePositive = truePositive;
            this.falseNegative = falseNegative;
            this.falsePositive = falsePositive;
            this.trueNegative = trueNegative;
            this.total = total;
            this.correctlyClassified = correctlyClassified;
            this.accuracyPercent = accuracyPercent;
        }
    }

    private static final class ChartGroup {
        private final String label;
        private final long actual;
        private final long estimated;
        private final boolean hasActual;

        private ChartGroup(
                String label,
                long actual,
                long estimated,
                boolean hasActual
        ) {
            this.label = label;
            this.actual = actual;
            this.estimated = estimated;
            this.hasActual = hasActual;
        }
    }
}
