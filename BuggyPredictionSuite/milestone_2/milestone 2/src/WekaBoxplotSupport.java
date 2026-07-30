import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class WekaBoxplotSupport {
    private WekaBoxplotSupport() {
    }

    enum Metric {
        PRECISION("Precision", "IR_precision", "precision"),
        RECALL("Recall", "IR_recall", "recall"),
        F1("F1", "F_measure", "f1"),
        AUC("AUC", "Area_under_ROC", "auc"),
        PRC("PRC", "Area_under_PRC", "prc"),
        KAPPA("Kappa", "Kappa_statistic", "kappa"),
        ACCURACY("Accuracy", "Percent_correct", "accuracy");

        final String displayName;
        final String csvColumn;
        final String fileName;

        Metric(String displayName, String csvColumn, String fileName) {
            this.displayName = displayName;
            this.csvColumn = csvColumn;
            this.fileName = fileName;
        }
    }

    enum ClassifierFamily {
        NAIVE_BAYES("NaiveBayes", 0),
        IBK("IBk", 1),
        RANDOM_FOREST("RandomForest", 2);

        final String displayName;
        final int order;

        ClassifierFamily(String displayName, int order) {
            this.displayName = displayName;
            this.order = order;
        }
    }

    static final class Configuration implements Comparable<Configuration> {
        final ClassifierFamily classifier;
        final boolean featureSelection;
        final boolean balancing;

        Configuration(ClassifierFamily classifier, boolean featureSelection, boolean balancing) {
            this.classifier = classifier;
            this.featureSelection = featureSelection;
            this.balancing = balancing;
        }

        String code() {
            String suffix;
            if (featureSelection && balancing) suffix = "FS_OVER";
            else if (featureSelection) suffix = "FS";
            else if (balancing) suffix = "OVER";
            else suffix = "PURE";

            String prefix = switch (classifier) {
                case NAIVE_BAYES -> "NB";
                case IBK -> "IBK";
                case RANDOM_FOREST -> "RF";
            };
            return prefix + "_" + suffix;
        }

        String[] axisLines() {
            return new String[]{
                    classifier.displayName,
                    "FS=" + (featureSelection ? "Yes" : "No")
                            + ", Bal=" + (balancing ? "Resample" : "No")
            };
        }

        private int variantOrder() {
            // Ordine coerente con i grafici di riferimento:
            // PURE, OVER, FS, FS_OVER.
            if (!featureSelection && !balancing) return 0;
            if (!featureSelection) return 1;
            if (!balancing) return 2;
            return 3;
        }

        @Override
        public int compareTo(Configuration other) {
            int byClassifier = Integer.compare(classifier.order, other.classifier.order);
            return byClassifier != 0 ? byClassifier : Integer.compare(variantOrder(), other.variantOrder());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Configuration that)) return false;
            return featureSelection == that.featureSelection
                    && balancing == that.balancing
                    && classifier == that.classifier;
        }

        @Override
        public int hashCode() {
            return Objects.hash(classifier, featureSelection, balancing);
        }
    }

    static final class Dataset {
        private final Map<Configuration, EnumMap<Metric, List<Double>>> values = new TreeMap<>();
        int rows;

        void add(Configuration config, Metric metric, double value) {
            values.computeIfAbsent(config, ignored -> new EnumMap<>(Metric.class))
                    .computeIfAbsent(metric, ignored -> new ArrayList<>())
                    .add(value);
        }

        List<Double> get(Configuration config, Metric metric) {
            return values.getOrDefault(config, new EnumMap<>(Metric.class))
                    .getOrDefault(metric, List.of());
        }

        List<Configuration> configurations() {
            return new ArrayList<>(values.keySet());
        }

        List<Configuration> configurations(ClassifierFamily family) {
            return values.keySet().stream()
                    .filter(c -> c.classifier == family)
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    static final class BoxStats {
        final int n;
        final double minimum;
        final double q1;
        final double median;
        final double mean;
        final double q3;
        final double maximum;
        final double lowerWhisker;
        final double upperWhisker;
        final List<Double> outliers;

        BoxStats(List<Double> source) {
            if (source.isEmpty()) {
                throw new IllegalArgumentException("Impossibile calcolare un boxplot senza valori.");
            }
            List<Double> values = new ArrayList<>(source);
            Collections.sort(values);
            n = values.size();
            minimum = values.get(0);
            maximum = values.get(values.size() - 1);
            q1 = quantile(values, 0.25);
            median = quantile(values, 0.50);
            q3 = quantile(values, 0.75);
            mean = values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();

            double iqr = q3 - q1;
            double lowerFence = q1 - 1.5 * iqr;
            double upperFence = q3 + 1.5 * iqr;

            lowerWhisker = values.stream().filter(v -> v >= lowerFence).findFirst().orElse(minimum);
            upperWhisker = values.stream().filter(v -> v <= upperFence)
                    .reduce((first, second) -> second).orElse(maximum);
            outliers = values.stream()
                    .filter(v -> v < lowerWhisker || v > upperWhisker)
                    .collect(Collectors.toList());
        }
    }

    static Path resolveInput(String[] args) throws IOException {
        if (args.length >= 1 && !args[0].isBlank()) {
            Path explicit = Paths.get(args[0]).toAbsolutePath().normalize();
            if (!Files.isRegularFile(explicit)) {
                throw new IOException("File CSV non trovato: " + explicit);
            }
            return explicit;
        }

        Path current = Paths.get("").toAbsolutePath().normalize();
        List<Path> roots = new ArrayList<>();
        Path cursor = current;
        for (int i = 0; i < 6 && cursor != null; i++) {
            roots.add(cursor);
            cursor = cursor.getParent();
        }

        List<String> relativeCandidates = List.of(
                "output/wekaResults.csv",
                "output/wekaResults(3).csv",
                "milestone 2/output/wekaResults.csv",
                "milestone_2/milestone 2/output/wekaResults.csv",
                "BuggyPredictionSuite/milestone_2/milestone 2/output/wekaResults.csv"
        );

        for (Path root : roots) {
            for (String candidate : relativeCandidates) {
                Path resolved = root.resolve(candidate).normalize();
                if (Files.isRegularFile(resolved)) return resolved;
            }
        }

        for (Path root : roots) {
            try (Stream<Path> stream = Files.find(root, 6,
                    (path, attrs) -> attrs.isRegularFile()
                            && path.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("wekaresults")
                            && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))) {
                Optional<Path> found = stream
                        .sorted(Comparator.comparing((Path p) -> !p.getFileName().toString().equalsIgnoreCase("wekaResults.csv")))
                        .findFirst();
                if (found.isPresent()) return found.get().toAbsolutePath().normalize();
            } catch (AccessDeniedException ignored) {
                // Prosegue con la radice successiva.
            }
        }

        throw new IOException(
                "File WEKA non trovato. Inserisci wekaResults.csv in milestone 2/output "
                        + "oppure passa il percorso completo come primo argomento.\nWorking directory: " + current
        );
    }

    static Path resolveOutput(String[] args, Path input) throws IOException {
        Path output = args.length >= 2 && !args[1].isBlank()
                ? Paths.get(args[1]).toAbsolutePath().normalize()
                : input.getParent().resolve("boxplots").toAbsolutePath().normalize();
        Files.createDirectories(output);
        return output;
    }

    static Dataset load(Path csvFile) throws IOException {
        Dataset dataset = new Dataset();
        try (BufferedReader reader = Files.newBufferedReader(csvFile, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) throw new IOException("CSV vuoto: " + csvFile);
            headerLine = headerLine.replace("\uFEFF", "");
            String[] header = headerLine.split(",", -1);
            Map<String, Integer> column = new HashMap<>();
            for (int i = 0; i < header.length; i++) column.put(header[i].trim(), i);

            requireColumns(column, "Key_Scheme", "Key_Scheme_options");
            for (Metric metric : Metric.values()) requireColumns(column, metric.csvColumn);

            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                String[] fields = parseWekaCsvLine(line, header.length);
                int lastRequiredIndex = Arrays.stream(Metric.values())
                        .mapToInt(metric -> column.get(metric.csvColumn))
                        .max().orElse(0);
                lastRequiredIndex = Math.max(lastRequiredIndex, column.get("Key_Scheme_options"));
                if (fields.length <= lastRequiredIndex) {
                    throw new IOException("Numero colonne non valido alla riga " + lineNumber
                            + ": servono almeno " + (lastRequiredIndex + 1)
                            + ", trovate " + fields.length);
                }
                if (fields.length < header.length) {
                    fields = Arrays.copyOf(fields, header.length);
                    for (int i = lastRequiredIndex + 1; i < fields.length; i++) {
                        if (fields[i] == null) fields[i] = "";
                    }
                }

                String scheme = fields[column.get("Key_Scheme")];
                String options = fields[column.get("Key_Scheme_options")];
                Configuration configuration = detectConfiguration(scheme, options);

                for (Metric metric : Metric.values()) {
                    String raw = fields[column.get(metric.csvColumn)].trim();
                    if (raw.isEmpty() || raw.equals("?")) continue;
                    try {
                        double value = Double.parseDouble(raw);
                        if (metric == Metric.ACCURACY) value /= 100.0;
                        if (Double.isFinite(value)) dataset.add(configuration, metric, value);
                    } catch (NumberFormatException e) {
                        throw new IOException("Valore non numerico per " + metric.csvColumn
                                + " alla riga " + lineNumber + ": " + raw, e);
                    }
                }
                dataset.rows++;
            }
        }

        validateDataset(dataset);
        return dataset;
    }

    private static void requireColumns(Map<String, Integer> column, String... names) throws IOException {
        for (String name : names) {
            if (!column.containsKey(name)) throw new IOException("Colonna mancante nel CSV: " + name);
        }
    }

    private static String[] parseWekaCsvLine(String original, int expectedColumns) {
        String line = original.strip();
        if (line.length() >= 2 && line.charAt(0) == '"' && line.charAt(line.length() - 1) == '"') {
            // Il CSV prodotto da WEKA può racchiudere l'intera riga tra virgolette.
            // Alcune esecuzioni omettono l'ultima misura opzionale: la validazione
            // delle colonne necessarie viene eseguita dal chiamante.
            String stripped = line.substring(1, line.length() - 1);
            return stripped.split(",", -1);
        }

        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(String[]::new);
    }

    private static Configuration detectConfiguration(String scheme, String options) throws IOException {
        String all = (scheme + " " + options).toLowerCase(Locale.ROOT);
        ClassifierFamily classifier;
        if (all.contains("naivebayes")) classifier = ClassifierFamily.NAIVE_BAYES;
        else if (all.contains("ibk")) classifier = ClassifierFamily.IBK;
        else if (all.contains("randomforest")) classifier = ClassifierFamily.RANDOM_FOREST;
        else throw new IOException("Classificatore non riconosciuto: " + scheme + " " + options);

        boolean featureSelection = all.contains("attributeselection") || all.contains("cfssubseteval");
        boolean balancing = all.contains("resample");
        return new Configuration(classifier, featureSelection, balancing);
    }

    private static void validateDataset(Dataset dataset) throws IOException {
        if (dataset.configurations().size() != 12) {
            throw new IOException("Attese 12 configurazioni, trovate " + dataset.configurations().size()
                    + ": " + dataset.configurations().stream().map(Configuration::code).toList());
        }
        for (Configuration configuration : dataset.configurations()) {
            for (Metric metric : Metric.values()) {
                int n = dataset.get(configuration, metric).size();
                if (n == 0) {
                    throw new IOException("Nessun valore per " + configuration.code() + " / " + metric.displayName);
                }
            }
        }
    }

    static void generateIndividualMetricPlots(Dataset dataset, Path output) throws IOException {
        for (Metric metric : Metric.values()) {
            BufferedImage image = renderMetricPlot(
                    dataset,
                    metric,
                    dataset.configurations(),
                    2400,
                    1400,
                    "Distribuzione " + metric.displayName + " across folds",
                    null
            );
            writePng(image, output.resolve("boxplot_" + metric.fileName + ".png"));
        }
    }

    static void generateCorePanel(Dataset dataset, Path output) throws IOException {
        List<Metric> metrics = List.of(Metric.PRECISION, Metric.RECALL, Metric.AUC, Metric.KAPPA);
        String[] captions = {
                "(a) Distribution of Precision across folds",
                "(b) Distribution of Recall across folds",
                "(c) Distribution of AUC across folds",
                "(d) Distribution of Kappa across folds"
        };
        int width = 3200;
        int height = 2000;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        configureGraphics(g);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);

        int gap = 38;
        int cellWidth = (width - gap * 3) / 2;
        int cellHeight = (height - gap * 3) / 2;
        for (int i = 0; i < metrics.size(); i++) {
            int col = i % 2;
            int row = i / 2;
            Rectangle cell = new Rectangle(
                    gap + col * (cellWidth + gap),
                    gap + row * (cellHeight + gap),
                    cellWidth,
                    cellHeight
            );
            drawPlot(g, cell, dataset, metrics.get(i), dataset.configurations(),
                    "Distribuzione " + metrics.get(i).displayName + " across folds", captions[i]);
        }
        g.dispose();
        writePng(image, output.resolve("boxplots_core_2x2.png"));
    }

    static void generateClassifierPanels(Dataset dataset, Path output) throws IOException {
        List<Metric> metrics = List.of(Metric.PRECISION, Metric.RECALL, Metric.AUC, Metric.KAPPA);
        for (ClassifierFamily family : ClassifierFamily.values()) {
            int width = 2500;
            int height = 1700;
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            configureGraphics(g);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);

            g.setColor(new Color(30, 30, 30));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
            drawCentered(g, "Distribuzione delle metriche — " + family.displayName, width / 2, 42);

            int gap = 34;
            int top = 65;
            int cellWidth = (width - gap * 3) / 2;
            int cellHeight = (height - top - gap * 3) / 2;
            List<Configuration> configurations = dataset.configurations(family);
            for (int i = 0; i < metrics.size(); i++) {
                int col = i % 2;
                int row = i / 2;
                Rectangle cell = new Rectangle(
                        gap + col * (cellWidth + gap),
                        top + gap + row * (cellHeight + gap),
                        cellWidth,
                        cellHeight
                );
                drawPlot(g, cell, dataset, metrics.get(i), configurations,
                        metrics.get(i).displayName + " across folds", null);
            }
            g.dispose();
            writePng(image, output.resolve("boxplots_" + family.displayName + ".png"));
        }
    }

    static BufferedImage renderMetricPlot(
            Dataset dataset,
            Metric metric,
            List<Configuration> configurations,
            int width,
            int height,
            String title,
            String caption
    ) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        configureGraphics(g);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        drawPlot(g, new Rectangle(0, 0, width, height), dataset, metric, configurations, title, caption);
        g.dispose();
        return image;
    }

    private static void drawPlot(
            Graphics2D g,
            Rectangle cell,
            Dataset dataset,
            Metric metric,
            List<Configuration> configurations,
            String title,
            String caption
    ) {
        Graphics2D local = (Graphics2D) g.create();
        local.clip(cell);
        local.setColor(Color.WHITE);
        local.fillRect(cell.x, cell.y, cell.width, cell.height);

        boolean manyLabels = configurations.size() > 6;
        int left = manyLabels ? 100 : 95;
        int right = 34;
        int top = 58;
        int bottom = manyLabels ? 285 : 185;
        if (caption != null) bottom += 45;

        int plotX = cell.x + left;
        int plotY = cell.y + top;
        int plotWidth = cell.width - left - right;
        int plotHeight = cell.height - top - bottom;

        local.setColor(new Color(30, 30, 30));
        local.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, manyLabels ? 20 : 23));
        drawCentered(local, title, cell.x + cell.width / 2, cell.y + 31);

        double[] range = yRange(dataset, configurations, metric);
        double yMin = range[0];
        double yMax = range[1];

        local.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, manyLabels ? 16 : 18));
        Stroke oldStroke = local.getStroke();
        for (int i = 0; i <= 10; i++) {
            double value = yMin + (yMax - yMin) * i / 10.0;
            int y = valueToY(value, yMin, yMax, plotY, plotHeight);
            local.setColor(new Color(220, 220, 220));
            local.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                    new float[]{5f, 5f}, 0f));
            local.drawLine(plotX, y, plotX + plotWidth, y);
            local.setStroke(oldStroke);
            local.setColor(new Color(45, 45, 45));
            String label = String.format(Locale.US, "%.1f", value);
            int labelWidth = local.getFontMetrics().stringWidth(label);
            local.drawString(label, plotX - labelWidth - 12, y + 6);
        }

        local.setColor(new Color(40, 40, 40));
        local.setStroke(new BasicStroke(1.6f));
        local.drawRect(plotX, plotY, plotWidth, plotHeight);

        int n = configurations.size();
        double spacing = plotWidth / (double) n;
        int boxWidth = (int) Math.max(24, Math.min(manyLabels ? 50 : 95, spacing * 0.52));

        for (int index = 0; index < n; index++) {
            Configuration configuration = configurations.get(index);
            List<Double> values = dataset.get(configuration, metric);
            BoxStats stats = new BoxStats(values);
            int centerX = (int) Math.round(plotX + spacing * (index + 0.5));
            drawBox(local, centerX, boxWidth, stats, yMin, yMax, plotY, plotHeight);

            if (manyLabels && (index == 4 || index == 8)) {
                int sepX = (int) Math.round(plotX + spacing * index);
                local.setColor(new Color(205, 205, 205));
                local.setStroke(new BasicStroke(1.2f));
                local.drawLine(sepX, plotY, sepX, plotY + plotHeight);
            }

            drawRotatedAxisLabel(local, configuration.axisLines(), centerX,
                    plotY + plotHeight + 24, manyLabels ? 16 : 20, manyLabels ? 48 : 34);
        }

        local.setColor(new Color(25, 25, 25));
        local.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, manyLabels ? 18 : 21));
        drawCentered(local, "Classifier / Configuration", plotX + plotWidth / 2,
                cell.y + cell.height - (caption == null ? 25 : 66));

        Graphics2D yLabelGraphics = (Graphics2D) local.create();
        yLabelGraphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, manyLabels ? 18 : 21));
        yLabelGraphics.setColor(new Color(25, 25, 25));
        yLabelGraphics.translate(cell.x + 27, plotY + plotHeight / 2);
        yLabelGraphics.rotate(-Math.PI / 2);
        drawCentered(yLabelGraphics, metric.displayName, 0, 0);
        yLabelGraphics.dispose();

        if (caption != null) {
            local.setFont(new Font(Font.SERIF, Font.PLAIN, 28));
            local.setColor(new Color(20, 20, 20));
            drawCentered(local, caption, cell.x + cell.width / 2, cell.y + cell.height - 18);
        }
        local.dispose();
    }

    private static void drawBox(
            Graphics2D g,
            int centerX,
            int boxWidth,
            BoxStats stats,
            double yMin,
            double yMax,
            int plotY,
            int plotHeight
    ) {
        int q1Y = valueToY(stats.q1, yMin, yMax, plotY, plotHeight);
        int q3Y = valueToY(stats.q3, yMin, yMax, plotY, plotHeight);
        int medianY = valueToY(stats.median, yMin, yMax, plotY, plotHeight);
        int meanY = valueToY(stats.mean, yMin, yMax, plotY, plotHeight);
        int lowerWhiskerY = valueToY(stats.lowerWhisker, yMin, yMax, plotY, plotHeight);
        int upperWhiskerY = valueToY(stats.upperWhisker, yMin, yMax, plotY, plotHeight);
        int half = boxWidth / 2;
        int capHalf = Math.max(8, boxWidth / 3);

        g.setColor(new Color(55, 55, 55));
        g.setStroke(new BasicStroke(1.8f));
        g.drawLine(centerX, upperWhiskerY, centerX, q3Y);
        g.drawLine(centerX, q1Y, centerX, lowerWhiskerY);
        g.drawLine(centerX - capHalf, upperWhiskerY, centerX + capHalf, upperWhiskerY);
        g.drawLine(centerX - capHalf, lowerWhiskerY, centerX + capHalf, lowerWhiskerY);

        int top = Math.min(q3Y, q1Y);
        int height = Math.max(1, Math.abs(q1Y - q3Y));
        g.setColor(new Color(250, 250, 250));
        g.fillRect(centerX - half, top, boxWidth, height);
        g.setColor(new Color(45, 45, 45));
        g.drawRect(centerX - half, top, boxWidth, height);

        g.setColor(new Color(255, 120, 40));
        g.setStroke(new BasicStroke(2.4f));
        g.drawLine(centerX - half, medianY, centerX + half, medianY);

        Polygon meanTriangle = new Polygon(
                new int[]{centerX, centerX - 7, centerX + 7},
                new int[]{meanY - 8, meanY + 7, meanY + 7},
                3
        );
        g.setColor(new Color(38, 155, 60));
        g.fillPolygon(meanTriangle);

        g.setStroke(new BasicStroke(1.5f));
        for (double outlier : stats.outliers) {
            int y = valueToY(outlier, yMin, yMax, plotY, plotHeight);
            g.setColor(Color.WHITE);
            g.fillOval(centerX - 5, y - 5, 10, 10);
            g.setColor(new Color(20, 20, 20));
            g.drawOval(centerX - 5, y - 5, 10, 10);
        }
    }

    private static void drawRotatedAxisLabel(Graphics2D g, String[] lines, int x, int y, int fontSize, int angleDegrees) {
        Graphics2D labelGraphics = (Graphics2D) g.create();
        labelGraphics.setColor(new Color(25, 25, 25));
        labelGraphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, fontSize));
        labelGraphics.translate(x, y);
        labelGraphics.rotate(Math.toRadians(angleDegrees));
        FontMetrics fm = labelGraphics.getFontMetrics();
        for (int i = 0; i < lines.length; i++) {
            labelGraphics.drawString(lines[i], 0, i * (fm.getHeight() - 2));
        }
        labelGraphics.dispose();
    }

    private static double[] yRange(Dataset dataset, List<Configuration> configurations, Metric metric) {
        double minimum = configurations.stream()
                .flatMap(c -> dataset.get(c, metric).stream())
                .mapToDouble(Double::doubleValue).min().orElse(0.0);
        double maximum = configurations.stream()
                .flatMap(c -> dataset.get(c, metric).stream())
                .mapToDouble(Double::doubleValue).max().orElse(1.0);

        double low = minimum < 0 ? Math.floor((minimum - 0.05) * 10.0) / 10.0 : 0.0;
        double high = maximum > 1 ? Math.ceil((maximum + 0.05) * 10.0) / 10.0 : 1.0;
        if (high - low < 0.2) {
            low = Math.max(-1.0, low - 0.1);
            high = Math.min(1.1, high + 0.1);
        }
        return new double[]{low, high};
    }

    private static int valueToY(double value, double min, double max, int plotY, int plotHeight) {
        double clamped = Math.max(min, Math.min(max, value));
        double ratio = (clamped - min) / (max - min);
        return plotY + plotHeight - (int) Math.round(ratio * plotHeight);
    }

    private static double quantile(List<Double> sortedValues, double probability) {
        if (sortedValues.size() == 1) return sortedValues.get(0);
        double position = probability * (sortedValues.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sortedValues.get(lower);
        double fraction = position - lower;
        return sortedValues.get(lower) * (1.0 - fraction) + sortedValues.get(upper) * fraction;
    }

    static void writeStatistics(Dataset dataset, Path output) throws IOException {
        Path file = output.resolve("boxplot_statistics.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("Configuration;Metric;N;Minimum;Q1;Median;Mean;Q3;Maximum;LowerWhisker;UpperWhisker;Outliers");
            writer.newLine();
            for (Configuration configuration : dataset.configurations()) {
                for (Metric metric : Metric.values()) {
                    BoxStats stats = new BoxStats(dataset.get(configuration, metric));
                    writer.write(String.join(";",
                            configuration.code(),
                            metric.displayName,
                            Integer.toString(stats.n),
                            decimal(stats.minimum),
                            decimal(stats.q1),
                            decimal(stats.median),
                            decimal(stats.mean),
                            decimal(stats.q3),
                            decimal(stats.maximum),
                            decimal(stats.lowerWhisker),
                            decimal(stats.upperWhisker),
                            Integer.toString(stats.outliers.size())
                    ));
                    writer.newLine();
                }
            }
        }
    }

    private static String decimal(double value) {
        return String.format(Locale.ITALY, "%.6f", value);
    }

    private static void configureGraphics(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    private static void drawCentered(Graphics2D g, String text, int centerX, int baselineY) {
        int width = g.getFontMetrics().stringWidth(text);
        g.drawString(text, centerX - width / 2, baselineY);
    }

    private static void writePng(BufferedImage image, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        if (!ImageIO.write(image, "png", file.toFile())) {
            throw new IOException("Nessun encoder PNG disponibile per: " + file);
        }
    }
}
