import java.io.BufferedReader;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Legge il CSV prodotto da WEKA Experimenter e genera il riepilogo delle
 * metriche per le 12 configurazioni della Milestone 2.
 *
 * Uso da IntelliJ:
 * 1. Mettere il CSV in: milestone 2/output/wekaResults.csv
 * 2. Eseguire direttamente questo main.
 *
 * Argomenti opzionali:
 *   args[0] = percorso del CSV di input
 *   args[1] = directory di output
 *
 * Output:
 *   output/wekaMetricsSummary.csv
 *   output/wekaMetricsSummary.md
 */
public final class GenerateWekaMetricsSummary {

    private static final List<String> CONFIGURATION_ORDER = Arrays.asList(
            "NB_PURE", "NB_FS", "NB_OVER", "NB_FS_OVER",
            "IBK_PURE", "IBK_FS", "IBK_OVER", "IBK_FS_OVER",
            "RF_PURE", "RF_FS", "RF_OVER", "RF_FS_OVER"
    );

    private static final DecimalFormat FOUR_DECIMALS;
    private static final DecimalFormat TWO_DECIMALS;

    static {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.ITALY);
        FOUR_DECIMALS = new DecimalFormat("0.0000", symbols);
        TWO_DECIMALS = new DecimalFormat("0.00", symbols);
    }

    private GenerateWekaMetricsSummary() {
        // Classe di utilità: non istanziabile.
    }

    public static void main(String[] args) {
        try {
            Path milestoneDirectory = resolveMilestoneDirectory();
            Path defaultOutputDirectory = milestoneDirectory.resolve("output");

            Path inputFile = args.length >= 1
                    ? Paths.get(args[0]).toAbsolutePath().normalize()
                    : defaultOutputDirectory.resolve("wekaResults.csv").toAbsolutePath().normalize();

            Path outputDirectory = args.length >= 2
                    ? Paths.get(args[1]).toAbsolutePath().normalize()
                    : defaultOutputDirectory.toAbsolutePath().normalize();

            if (!Files.isRegularFile(inputFile)) {
                throw new IOException(
                        "File WEKA non trovato: " + inputFile + System.lineSeparator()
                                + "Inserisci il CSV in milestone 2/output/wekaResults.csv "
                                + "oppure passa il percorso come primo argomento."
                );
            }

            Files.createDirectories(outputDirectory);

            Map<String, ConfigurationAccumulator> configurations = readWekaResults(inputFile);
            validateExperiment(configurations);
            Map<String, Metrics> summaries = computeSummaries(configurations);

            Path csvOutput = outputDirectory.resolve("wekaMetricsSummary.csv");
            Path markdownOutput = outputDirectory.resolve("wekaMetricsSummary.md");

            writeCsv(csvOutput, summaries);
            writeMarkdown(markdownOutput, summaries);

            System.out.println("Analisi completata correttamente.");
            System.out.println("Input:  " + inputFile);
            System.out.println("CSV:    " + csvOutput);
            System.out.println("Tabella Markdown: " + markdownOutput);
            System.out.println();
            printTableToConsole(summaries);
        } catch (Exception exception) {
            System.err.println("Errore durante la generazione del riepilogo:");
            System.err.println(exception.getMessage());
            exception.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static Path resolveMilestoneDirectory() {
        Path current = Paths.get("").toAbsolutePath().normalize();

        if (current.getFileName() != null
                && "milestone 2".equalsIgnoreCase(current.getFileName().toString())) {
            return current;
        }

        Path child = current.resolve("milestone 2");
        if (Files.isDirectory(child)) {
            return child;
        }

        // Consente di avviare il main anche con working directory impostata
        // direttamente sulla cartella che contiene il file Java.
        return current;
    }

    private static Map<String, ConfigurationAccumulator> readWekaResults(Path inputFile)
            throws IOException {

        Map<String, ConfigurationAccumulator> configurations = new LinkedHashMap<>();

        try (BufferedReader reader = Files.newBufferedReader(inputFile, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IOException("Il CSV è vuoto.");
            }

            headerLine = removeUtf8Bom(headerLine);
            List<String> headers = splitWekaLine(headerLine);
            Map<String, Integer> indexes = createHeaderIndex(headers);
            requireColumns(indexes,
                    "Key_Run",
                    "Key_Scheme",
                    "Key_Scheme_options",
                    "Num_true_positives",
                    "Num_false_positives",
                    "Num_true_negatives",
                    "Num_false_negatives",
                    "Area_under_ROC",
                    "Area_under_PRC"
            );

            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.trim().isEmpty()) {
                    continue;
                }

                List<String> values = splitWekaLine(line);
                while (values.size() < headers.size()) {
                    values.add("");
                }
                if (values.size() > headers.size()) {
                    throw new IOException(
                            "Riga " + lineNumber + " non valida: trovate "
                                    + values.size() + " colonne invece di " + headers.size() + "."
                    );
                }

                String scheme = value(values, indexes, "Key_Scheme");
                String options = value(values, indexes, "Key_Scheme_options");
                String configurationName = identifyConfiguration(scheme, options);
                int run = parseInteger(value(values, indexes, "Key_Run"), "Key_Run", lineNumber);

                double truePositives = parseDouble(
                        value(values, indexes, "Num_true_positives"),
                        "Num_true_positives",
                        lineNumber
                );
                double falsePositives = parseDouble(
                        value(values, indexes, "Num_false_positives"),
                        "Num_false_positives",
                        lineNumber
                );
                double trueNegatives = parseDouble(
                        value(values, indexes, "Num_true_negatives"),
                        "Num_true_negatives",
                        lineNumber
                );
                double falseNegatives = parseDouble(
                        value(values, indexes, "Num_false_negatives"),
                        "Num_false_negatives",
                        lineNumber
                );
                double auc = parseDouble(
                        value(values, indexes, "Area_under_ROC"),
                        "Area_under_ROC",
                        lineNumber
                );
                double prc = parseDouble(
                        value(values, indexes, "Area_under_PRC"),
                        "Area_under_PRC",
                        lineNumber
                );

                ConfigurationAccumulator configuration = configurations.computeIfAbsent(
                        configurationName,
                        ignored -> new ConfigurationAccumulator()
                );
                RunAccumulator runAccumulator = configuration.runs.computeIfAbsent(
                        run,
                        ignored -> new RunAccumulator()
                );

                runAccumulator.truePositives += truePositives;
                runAccumulator.falsePositives += falsePositives;
                runAccumulator.trueNegatives += trueNegatives;
                runAccumulator.falseNegatives += falseNegatives;
                runAccumulator.aucSum += auc;
                runAccumulator.prcSum += prc;
                runAccumulator.foldCount++;
                configuration.rowCount++;
            }
        }

        return configurations;
    }

    /**
     * I CSV prodotti/ricongiunti da WEKA possono contenere ogni riga racchiusa
     * in una coppia di virgolette e le virgolette interne raddoppiate. Le
     * opzioni delle pipeline non contengono virgole, quindi dopo la rimozione
     * dell'involucro esterno è sufficiente lo split preservando i campi vuoti.
     */
    private static List<String> splitWekaLine(String originalLine) {
        String line = originalLine;

        if (line.length() >= 2 && line.startsWith("\"") && line.endsWith("\"")) {
            line = line.substring(1, line.length() - 1).replace("\"\"", "\"");
        }

        return new ArrayList<>(Arrays.asList(line.split(",", -1)));
    }

    private static String removeUtf8Bom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private static Map<String, Integer> createHeaderIndex(List<String> headers) {
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            indexes.put(headers.get(i).trim(), i);
        }
        return indexes;
    }

    private static void requireColumns(Map<String, Integer> indexes, String... requiredColumns)
            throws IOException {
        for (String column : requiredColumns) {
            if (!indexes.containsKey(column)) {
                throw new IOException("Colonna obbligatoria non trovata nel CSV: " + column);
            }
        }
    }

    private static String value(
            List<String> values,
            Map<String, Integer> indexes,
            String columnName
    ) {
        return values.get(indexes.get(columnName)).trim();
    }

    private static String identifyConfiguration(String scheme, String options) throws IOException {
        String descriptor = scheme + " " + options;

        String classifier;
        if (descriptor.contains("NaiveBayes")) {
            classifier = "NB";
        } else if (descriptor.contains("IBk")) {
            classifier = "IBK";
        } else if (descriptor.contains("RandomForest")) {
            classifier = "RF";
        } else {
            throw new IOException("Classificatore non riconosciuto: " + descriptor);
        }

        boolean featureSelection = descriptor.contains("AttributeSelection")
                || descriptor.contains("CfsSubsetEval");
        boolean oversampling = descriptor.contains("Resample");

        if (featureSelection && oversampling) {
            return classifier + "_FS_OVER";
        }
        if (featureSelection) {
            return classifier + "_FS";
        }
        if (oversampling) {
            return classifier + "_OVER";
        }
        return classifier + "_PURE";
    }

    private static int parseInteger(String text, String columnName, int lineNumber)
            throws IOException {
        try {
            return (int) Math.round(Double.parseDouble(text));
        } catch (NumberFormatException exception) {
            throw new IOException(
                    "Valore non numerico nella colonna " + columnName
                            + " alla riga " + lineNumber + ": " + text,
                    exception
            );
        }
    }

    private static double parseDouble(String text, String columnName, int lineNumber)
            throws IOException {
        if (text.isEmpty() || "?".equals(text)) {
            throw new IOException(
                    "Valore mancante nella colonna " + columnName + " alla riga " + lineNumber + "."
            );
        }

        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException exception) {
            throw new IOException(
                    "Valore non numerico nella colonna " + columnName
                            + " alla riga " + lineNumber + ": " + text,
                    exception
            );
        }
    }

    private static void validateExperiment(Map<String, ConfigurationAccumulator> configurations)
            throws IOException {
        List<String> missing = new ArrayList<>();
        for (String configuration : CONFIGURATION_ORDER) {
            if (!configurations.containsKey(configuration)) {
                missing.add(configuration);
            }
        }

        if (!missing.isEmpty()) {
            throw new IOException("Configurazioni mancanti: " + String.join(", ", missing));
        }

        for (String configurationName : CONFIGURATION_ORDER) {
            ConfigurationAccumulator configuration = configurations.get(configurationName);

            if (configuration.rowCount != 100) {
                throw new IOException(
                        configurationName + " contiene " + configuration.rowCount
                                + " righe; ne erano attese 100 (10 run × 10 fold)."
                );
            }

            if (configuration.runs.size() != 10) {
                throw new IOException(
                        configurationName + " contiene " + configuration.runs.size()
                                + " run; ne erano attese 10."
                );
            }

            for (int run = 1; run <= 10; run++) {
                RunAccumulator accumulator = configuration.runs.get(run);
                if (accumulator == null) {
                    throw new IOException(configurationName + ": run " + run + " mancante.");
                }
                if (accumulator.foldCount != 10) {
                    throw new IOException(
                            configurationName + ", run " + run + ": trovati "
                                    + accumulator.foldCount + " fold invece di 10."
                    );
                }
            }
        }
    }

    private static Map<String, Metrics> computeSummaries(
            Map<String, ConfigurationAccumulator> configurations
    ) {
        Map<String, Metrics> result = new LinkedHashMap<>();

        for (String configurationName : CONFIGURATION_ORDER) {
            ConfigurationAccumulator configuration = configurations.get(configurationName);
            List<Integer> runNumbers = new ArrayList<>(configuration.runs.keySet());
            Collections.sort(runNumbers);

            List<Metrics> runMetrics = new ArrayList<>();
            for (Integer runNumber : runNumbers) {
                RunAccumulator run = configuration.runs.get(runNumber);
                runMetrics.add(calculateRunMetrics(run));
            }

            result.put(configurationName, average(runMetrics));
        }

        return result;
    }

    private static Metrics calculateRunMetrics(RunAccumulator run) {
        double tp = run.truePositives;
        double fp = run.falsePositives;
        double tn = run.trueNegatives;
        double fn = run.falseNegatives;

        double precision = divide(tp, tp + fp);
        double recall = divide(tp, tp + fn);
        double f1 = divide(2.0 * precision * recall, precision + recall);
        double accuracy = 100.0 * divide(tp + tn, tp + fp + tn + fn);
        double kappa = calculateKappa(tp, fp, tn, fn);
        double auc = divide(run.aucSum, run.foldCount);
        double prc = divide(run.prcSum, run.foldCount);

        return new Metrics(precision, recall, f1, auc, prc, kappa, accuracy);
    }

    private static double calculateKappa(double tp, double fp, double tn, double fn) {
        double total = tp + fp + tn + fn;
        double observedAgreement = divide(tp + tn, total);

        double predictedPositive = tp + fp;
        double predictedNegative = tn + fn;
        double actualPositive = tp + fn;
        double actualNegative = tn + fp;

        double expectedAgreement = divide(
                predictedPositive * actualPositive + predictedNegative * actualNegative,
                total * total
        );

        return divide(observedAgreement - expectedAgreement, 1.0 - expectedAgreement);
    }

    private static Metrics average(List<Metrics> metrics) {
        double precision = 0.0;
        double recall = 0.0;
        double f1 = 0.0;
        double auc = 0.0;
        double prc = 0.0;
        double kappa = 0.0;
        double accuracy = 0.0;

        for (Metrics metric : metrics) {
            precision += metric.precision;
            recall += metric.recall;
            f1 += metric.f1;
            auc += metric.auc;
            prc += metric.prc;
            kappa += metric.kappa;
            accuracy += metric.accuracy;
        }

        int size = metrics.size();
        return new Metrics(
                precision / size,
                recall / size,
                f1 / size,
                auc / size,
                prc / size,
                kappa / size,
                accuracy / size
        );
    }

    private static double divide(double numerator, double denominator) {
        return denominator == 0.0 ? 0.0 : numerator / denominator;
    }

    private static void writeCsv(Path outputFile, Map<String, Metrics> summaries)
            throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            // BOM per una migliore apertura diretta con Excel su Windows.
            writer.write('\uFEFF');
            writer.write("Configurazione;Precision;Recall;F1;AUC;PRC;Kappa;Accuracy");
            writer.newLine();

            for (String configuration : CONFIGURATION_ORDER) {
                Metrics metrics = summaries.get(configuration);
                writer.write(configuration);
                writer.write(';');
                writer.write(formatFour(metrics.precision));
                writer.write(';');
                writer.write(formatFour(metrics.recall));
                writer.write(';');
                writer.write(formatFour(metrics.f1));
                writer.write(';');
                writer.write(formatFour(metrics.auc));
                writer.write(';');
                writer.write(formatFour(metrics.prc));
                writer.write(';');
                writer.write(formatFour(metrics.kappa));
                writer.write(';');
                writer.write(formatTwo(metrics.accuracy) + "%");
                writer.newLine();
            }
        }
    }

    private static void writeMarkdown(Path outputFile, Map<String, Metrics> summaries)
            throws IOException {
        MetricMaxima maxima = calculateMaxima(summaries);

        try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            writer.write("| Configurazione | Precision | Recall | F1 | AUC | PRC | Kappa | Accuracy |");
            writer.newLine();
            writer.write("|---|---:|---:|---:|---:|---:|---:|---:|");
            writer.newLine();

            for (String configuration : CONFIGURATION_ORDER) {
                Metrics metrics = summaries.get(configuration);
                String configurationCell = "RF_OVER".equals(configuration)
                        ? "**" + configuration + "**"
                        : configuration;

                writer.write("| " + configurationCell);
                writer.write(" | " + highlight(formatFour(metrics.precision), metrics.precision, maxima.precision));
                writer.write(" | " + highlight(formatFour(metrics.recall), metrics.recall, maxima.recall));
                writer.write(" | " + highlight(formatFour(metrics.f1), metrics.f1, maxima.f1));
                writer.write(" | " + highlight(formatFour(metrics.auc), metrics.auc, maxima.auc));
                writer.write(" | " + highlight(formatFour(metrics.prc), metrics.prc, maxima.prc));
                writer.write(" | " + highlight(formatFour(metrics.kappa), metrics.kappa, maxima.kappa));
                writer.write(" | " + highlight(formatTwo(metrics.accuracy) + "%", metrics.accuracy, maxima.accuracy));
                writer.write(" |");
                writer.newLine();
            }
        }
    }

    private static void printTableToConsole(Map<String, Metrics> summaries) {
        String format = "%-14s %10s %10s %10s %10s %10s %10s %11s%n";
        System.out.printf(
                format,
                "Configurazione", "Precision", "Recall", "F1", "AUC", "PRC", "Kappa", "Accuracy"
        );

        for (String configuration : CONFIGURATION_ORDER) {
            Metrics metrics = summaries.get(configuration);
            System.out.printf(
                    format,
                    configuration,
                    formatFour(metrics.precision),
                    formatFour(metrics.recall),
                    formatFour(metrics.f1),
                    formatFour(metrics.auc),
                    formatFour(metrics.prc),
                    formatFour(metrics.kappa),
                    formatTwo(metrics.accuracy) + "%"
            );
        }
    }

    private static MetricMaxima calculateMaxima(Map<String, Metrics> summaries) {
        MetricMaxima maxima = new MetricMaxima();
        maxima.precision = Double.NEGATIVE_INFINITY;
        maxima.recall = Double.NEGATIVE_INFINITY;
        maxima.f1 = Double.NEGATIVE_INFINITY;
        maxima.auc = Double.NEGATIVE_INFINITY;
        maxima.prc = Double.NEGATIVE_INFINITY;
        maxima.kappa = Double.NEGATIVE_INFINITY;
        maxima.accuracy = Double.NEGATIVE_INFINITY;

        for (Metrics metrics : summaries.values()) {
            maxima.precision = Math.max(maxima.precision, metrics.precision);
            maxima.recall = Math.max(maxima.recall, metrics.recall);
            maxima.f1 = Math.max(maxima.f1, metrics.f1);
            maxima.auc = Math.max(maxima.auc, metrics.auc);
            maxima.prc = Math.max(maxima.prc, metrics.prc);
            maxima.kappa = Math.max(maxima.kappa, metrics.kappa);
            maxima.accuracy = Math.max(maxima.accuracy, metrics.accuracy);
        }

        return maxima;
    }

    private static String highlight(String formatted, double value, double maximum) {
        return Math.abs(value - maximum) < 1.0e-12
                ? "**" + formatted + "**"
                : formatted;
    }

    private static String formatFour(double value) {
        synchronized (FOUR_DECIMALS) {
            return FOUR_DECIMALS.format(value);
        }
    }

    private static String formatTwo(double value) {
        synchronized (TWO_DECIMALS) {
            return TWO_DECIMALS.format(value);
        }
    }

    private static final class ConfigurationAccumulator {
        private final Map<Integer, RunAccumulator> runs = new LinkedHashMap<>();
        private int rowCount;
    }

    private static final class RunAccumulator {
        private double truePositives;
        private double falsePositives;
        private double trueNegatives;
        private double falseNegatives;
        private double aucSum;
        private double prcSum;
        private int foldCount;
    }

    private static final class Metrics {
        private final double precision;
        private final double recall;
        private final double f1;
        private final double auc;
        private final double prc;
        private final double kappa;
        private final double accuracy;

        private Metrics(
                double precision,
                double recall,
                double f1,
                double auc,
                double prc,
                double kappa,
                double accuracy
        ) {
            this.precision = precision;
            this.recall = recall;
            this.f1 = f1;
            this.auc = auc;
            this.prc = prc;
            this.kappa = kappa;
            this.accuracy = accuracy;
        }
    }

    private static final class MetricMaxima {
        private double precision;
        private double recall;
        private double f1;
        private double auc;
        private double prc;
        private double kappa;
        private double accuracy;
    }
}
