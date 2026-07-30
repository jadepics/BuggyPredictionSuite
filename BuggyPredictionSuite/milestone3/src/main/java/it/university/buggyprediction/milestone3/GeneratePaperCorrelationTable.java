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
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Milestone 3 - Tabella di correlazione nello stile del paper
 * "What if I had no smells?".
 *
 * Esecuzione:
 * 1. Aprire questa classe in IntelliJ.
 * 2. Premere il triangolo verde accanto a main().
 *
 * Input cercati automaticamente in milestone3/output:
 * - A.csv
 * - B_plus.csv (sono accettati anche B_Plus.csv, B+.csv e Bplus.csv)
 * - C.csv
 *
 * Se A.csv non e' in milestone3/output viene cercato anche in
 * milestone-1/output, per compatibilita' con la struttura precedente.
 *
 * Come nel paper, il dataset B viene costruito virtualmente a partire da B+,
 * mantenendo tutte le feature invariate e forzando NSmells a 0.
 *
 * Output:
 * milestone3/output/correlation-paper/
 * - correlation_feature_defectiveness.csv
 * - correlation_feature_defectiveness_details.csv
 * - correlation_feature_defectiveness.png
 * - correlation_feature_defectiveness.html
 * - correlation_feature_defectiveness_report.txt
 */
public final class GeneratePaperCorrelationTable {

    private static final double ALPHA = 0.05;
    private static final List<String> NSMELLS_ALIASES = List.of(
            "nsmells", "nsmell", "#smells", "smells", "numberofsmells");
    private static final List<String> TARGET_ALIASES = List.of(
            "buggy", "defectiveness", "defective", "isbuggy", "isdefective");
    private static final Set<String> EXCLUDED_IDENTIFIER_COLUMNS = Set.of(
            "release",
            "classname",
            "class",
            "filepath",
            "filename",
            "smellsourcerelease",
            "pmdrules",
            "pmdanalysisstatus");

    private static final DecimalFormat NUMBER_FORMAT;
    private static final DecimalFormat P_VALUE_FORMAT;

    static {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.ROOT);
        NUMBER_FORMAT = new DecimalFormat("0.######", symbols);
        P_VALUE_FORMAT = new DecimalFormat("0.##########", symbols);
    }

    private GeneratePaperCorrelationTable() {
    }

    public static void main(final String[] args) {
        try {
            Path repositoryRoot = locateRepositoryRoot();
            Path milestone3Output = repositoryRoot.resolve("milestone3").resolve("output");

            Path aPath = findA(milestone3Output, repositoryRoot);
            Path bPlusPath = findFileIgnoreCase(
                    milestone3Output,
                    List.of("B_plus.csv", "B_Plus.csv", "B+.csv", "Bplus.csv"));
            Path cPath = findFileIgnoreCase(milestone3Output, List.of("C.csv"));

            Dataset datasetA = Dataset.read(aPath);
            Dataset datasetBPlus = Dataset.read(bPlusPath);
            Dataset datasetC = Dataset.read(cPath);

            AnalysisResult result = analyze(datasetA, datasetBPlus, datasetC);

            Path outputDirectory = milestone3Output.resolve("correlation-paper");
            Files.createDirectories(outputDirectory);

            Path displayCsv = outputDirectory.resolve("correlation_feature_defectiveness.csv");
            Path detailsCsv = outputDirectory.resolve("correlation_feature_defectiveness_details.csv");
            Path png = outputDirectory.resolve("correlation_feature_defectiveness.png");
            Path html = outputDirectory.resolve("correlation_feature_defectiveness.html");
            Path report = outputDirectory.resolve("correlation_feature_defectiveness_report.txt");

            writeDisplayCsv(displayCsv, result);
            writeDetailsCsv(detailsCsv, result);
            writeHtml(html, result);
            writePng(png, result);
            writeReport(report, aPath, bPlusPath, cPath, result);

            String message = String.format(Locale.ROOT,
                    "Analisi di correlazione completata.%n%n"
                            + "Metodo: correlazione di Spearman (rho)%n"
                            + "Significativita': * quando p < %.2f%n"
                            + "Dataset B: derivato da B+ forzando NSmells a 0%n%n"
                            + "Feature analizzate: %d%n"
                            + "Righe A: %d%n"
                            + "Righe B+: %d%n"
                            + "Righe C: %d%n%n"
                            + "Output:%n%s",
                    ALPHA,
                    result.rows.size(),
                    result.sizeA,
                    result.sizeBPlus,
                    result.sizeC,
                    outputDirectory.toAbsolutePath().normalize());

            System.out.println(message);
            showDialog("Milestone 3 - Correlazione", message, JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception exception) {
            String message = "Impossibile calcolare la correlazione.\n\n"
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage();
            System.err.println(message);
            exception.printStackTrace(System.err);
            showDialog("Errore - Correlazione", message, JOptionPane.ERROR_MESSAGE);
            throw new IllegalStateException(message, exception);
        }
    }

    private static AnalysisResult analyze(
            final Dataset datasetA,
            final Dataset datasetBPlus,
            final Dataset datasetC) {

        String nSmellsColumn = findRequiredColumn(datasetA.header, NSMELLS_ALIASES, "NSmells");
        String targetColumn = findRequiredColumn(datasetA.header, TARGET_ALIASES, "Buggy/Defectiveness");

        Map<String, String> bHeaders = normalizedHeaderMap(datasetBPlus.header);
        Map<String, String> cHeaders = normalizedHeaderMap(datasetC.header);

        List<String> featureColumns = new ArrayList<>();
        List<String> excludedColumns = new ArrayList<>();

        for (String column : datasetA.header) {
            String normalized = normalizeName(column);
            if (normalized.equals(normalizeName(targetColumn))) {
                continue;
            }
            if (EXCLUDED_IDENTIFIER_COLUMNS.contains(normalized)) {
                excludedColumns.add(column + " (identificatore/testo)");
                continue;
            }
            if (!bHeaders.containsKey(normalized) || !cHeaders.containsKey(normalized)) {
                excludedColumns.add(column + " (assente in B+ o C)");
                continue;
            }
            if (!datasetA.isNumericColumn(column)
                    || !datasetBPlus.isNumericColumn(bHeaders.get(normalized))
                    || !datasetC.isNumericColumn(cHeaders.get(normalized))) {
                excludedColumns.add(column + " (non numerica)");
                continue;
            }
            featureColumns.add(column);
        }

        if (featureColumns.isEmpty()) {
            throw new IllegalArgumentException("Nessuna feature numerica comune trovata in A, B+ e C.");
        }

        String nSmellsInB = bHeaders.get(normalizeName(nSmellsColumn));
        String nSmellsInC = cHeaders.get(normalizeName(nSmellsColumn));
        if (nSmellsInB == null || nSmellsInC == null) {
            throw new IllegalArgumentException("La colonna NSmells deve esistere anche in B+ e C.");
        }

        double[] smellsA = datasetA.numericColumn(nSmellsColumn);
        double[] defectivenessA = datasetA.binaryTargetColumn(targetColumn);

        List<TableRow> rows = new ArrayList<>();
        for (String featureA : featureColumns) {
            String normalized = normalizeName(featureA);
            String featureB = bHeaders.get(normalized);
            String featureC = cHeaders.get(normalized);

            double meanA = mean(datasetA.numericColumn(featureA));
            double meanB = normalized.equals(normalizeName(nSmellsColumn))
                    ? 0.0
                    : mean(datasetBPlus.numericColumn(featureB));
            double meanC = mean(datasetC.numericColumn(featureC));

            double[] featureValuesA = datasetA.numericColumn(featureA);
            CorrelationResult withSmells;
            if (normalized.equals(normalizeName(nSmellsColumn))) {
                withSmells = CorrelationResult.selfCorrelation();
            } else {
                withSmells = spearman(featureValuesA, smellsA);
            }
            CorrelationResult withDefectiveness = spearman(featureValuesA, defectivenessA);

            rows.add(new TableRow(
                    featureA,
                    meanA,
                    meanB,
                    meanC,
                    withSmells,
                    withDefectiveness));
        }

        List<String> warnings = new ArrayList<>();
        if (datasetBPlus.rows.size() + datasetC.rows.size() != datasetA.rows.size()) {
            warnings.add("B+ e C non sommano esattamente al numero di righe di A: "
                    + datasetBPlus.rows.size() + " + " + datasetC.rows.size()
                    + " != " + datasetA.rows.size() + ".");
        }

        return new AnalysisResult(
                datasetA.rows.size(),
                datasetBPlus.rows.size(),
                datasetC.rows.size(),
                nSmellsColumn,
                targetColumn,
                rows,
                excludedColumns,
                warnings);
    }

    private static CorrelationResult spearman(final double[] rawX, final double[] rawY) {
        if (rawX.length != rawY.length) {
            throw new IllegalArgumentException("Le due serie hanno dimensioni differenti.");
        }

        List<Double> xValues = new ArrayList<>();
        List<Double> yValues = new ArrayList<>();
        for (int index = 0; index < rawX.length; index++) {
            double x = rawX[index];
            double y = rawY[index];
            if (Double.isFinite(x) && Double.isFinite(y)) {
                xValues.add(x);
                yValues.add(y);
            }
        }

        int n = xValues.size();
        if (n < 3) {
            return CorrelationResult.undefined(n);
        }

        double[] x = xValues.stream().mapToDouble(Double::doubleValue).toArray();
        double[] y = yValues.stream().mapToDouble(Double::doubleValue).toArray();

        double[] rankX = averageRanks(x);
        double[] rankY = averageRanks(y);
        double rho = pearson(rankX, rankY);

        if (!Double.isFinite(rho)) {
            return CorrelationResult.undefined(n);
        }

        double pValue = twoSidedCorrelationPValue(rho, n);
        return new CorrelationResult(rho, pValue, n, false);
    }

    private static double[] averageRanks(final double[] values) {
        Integer[] order = new Integer[values.length];
        for (int index = 0; index < values.length; index++) {
            order[index] = index;
        }
        Arrays.sort(order, Comparator.comparingDouble(index -> values[index]));

        double[] ranks = new double[values.length];
        int start = 0;
        while (start < order.length) {
            int end = start + 1;
            while (end < order.length
                    && Double.compare(values[order[start]], values[order[end]]) == 0) {
                end++;
            }
            double averageRank = ((start + 1) + end) / 2.0;
            for (int index = start; index < end; index++) {
                ranks[order[index]] = averageRank;
            }
            start = end;
        }
        return ranks;
    }

    private static double pearson(final double[] x, final double[] y) {
        double meanX = mean(x);
        double meanY = mean(y);
        double numerator = 0.0;
        double sumSquaresX = 0.0;
        double sumSquaresY = 0.0;

        for (int index = 0; index < x.length; index++) {
            double dx = x[index] - meanX;
            double dy = y[index] - meanY;
            numerator += dx * dy;
            sumSquaresX += dx * dx;
            sumSquaresY += dy * dy;
        }

        if (sumSquaresX == 0.0 || sumSquaresY == 0.0) {
            return Double.NaN;
        }
        return numerator / Math.sqrt(sumSquaresX * sumSquaresY);
    }

    /**
     * P-value bilaterale tramite l'approssimazione t comunemente usata per
     * verificare la significativita' di una correlazione:
     * t = rho * sqrt((n-2)/(1-rho^2)), df = n-2.
     */
    private static double twoSidedCorrelationPValue(final double rho, final int n) {
        double absolute = Math.abs(rho);
        if (absolute >= 1.0) {
            return 0.0;
        }
        double denominator = 1.0 - absolute * absolute;
        if (denominator <= 0.0) {
            return 0.0;
        }
        double t = absolute * Math.sqrt((n - 2.0) / denominator);
        double cdf = studentTCdf(t, n - 2.0);
        return clamp(2.0 * (1.0 - cdf), 0.0, 1.0);
    }

    private static double studentTCdf(final double t, final double degreesOfFreedom) {
        if (degreesOfFreedom <= 0.0) {
            return Double.NaN;
        }
        if (t == 0.0) {
            return 0.5;
        }
        double x = degreesOfFreedom / (degreesOfFreedom + t * t);
        double beta = regularizedBeta(x, degreesOfFreedom / 2.0, 0.5);
        return t > 0.0 ? 1.0 - 0.5 * beta : 0.5 * beta;
    }

    private static double regularizedBeta(final double x, final double a, final double b) {
        if (x <= 0.0) {
            return 0.0;
        }
        if (x >= 1.0) {
            return 1.0;
        }

        double logarithm = logGamma(a + b) - logGamma(a) - logGamma(b)
                + a * Math.log(x) + b * Math.log1p(-x);
        double factor = Math.exp(logarithm);

        if (x < (a + 1.0) / (a + b + 2.0)) {
            return clamp(factor * betaContinuedFraction(x, a, b) / a, 0.0, 1.0);
        }
        return clamp(1.0 - factor * betaContinuedFraction(1.0 - x, b, a) / b, 0.0, 1.0);
    }

    private static double betaContinuedFraction(final double x, final double a, final double b) {
        final int maxIterations = 10000;
        final double epsilon = 3.0e-14;
        final double tiny = 1.0e-300;

        double qab = a + b;
        double qap = a + 1.0;
        double qam = a - 1.0;
        double c = 1.0;
        double d = 1.0 - qab * x / qap;
        if (Math.abs(d) < tiny) {
            d = tiny;
        }
        d = 1.0 / d;
        double h = d;

        for (int m = 1; m <= maxIterations; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1.0 + aa * d;
            if (Math.abs(d) < tiny) {
                d = tiny;
            }
            c = 1.0 + aa / c;
            if (Math.abs(c) < tiny) {
                c = tiny;
            }
            d = 1.0 / d;
            h *= d * c;

            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1.0 + aa * d;
            if (Math.abs(d) < tiny) {
                d = tiny;
            }
            c = 1.0 + aa / c;
            if (Math.abs(c) < tiny) {
                c = tiny;
            }
            d = 1.0 / d;
            double delta = d * c;
            h *= delta;

            if (Math.abs(delta - 1.0) < epsilon) {
                break;
            }
        }
        return h;
    }

    private static double logGamma(final double value) {
        double[] coefficients = {
                676.5203681218851,
                -1259.1392167224028,
                771.32342877765313,
                -176.61502916214059,
                12.507343278686905,
                -0.13857109526572012,
                9.9843695780195716e-6,
                1.5056327351493116e-7
        };

        if (value < 0.5) {
            return Math.log(Math.PI) - Math.log(Math.sin(Math.PI * value)) - logGamma(1.0 - value);
        }

        double shifted = value - 1.0;
        double sum = 0.99999999999980993;
        for (int index = 0; index < coefficients.length; index++) {
            sum += coefficients[index] / (shifted + index + 1.0);
        }
        double t = shifted + coefficients.length - 0.5;
        return 0.5 * Math.log(2.0 * Math.PI)
                + (shifted + 0.5) * Math.log(t)
                - t
                + Math.log(sum);
    }

    private static void writeDisplayCsv(final Path output, final AnalysisResult result) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writeCsvRecord(writer, List.of(
                    "Variable",
                    "DatasetA",
                    "DatasetB+",
                    "DatasetC",
                    "NSmells",
                    "Defectiveness"));
            writeCsvRecord(writer, List.of(
                    "Size",
                    Integer.toString(result.sizeA),
                    Integer.toString(result.sizeBPlus),
                    Integer.toString(result.sizeC),
                    "",
                    ""));

            for (TableRow row : result.rows) {
                writeCsvRecord(writer, List.of(
                        row.variable,
                        formatNumber(row.meanA),
                        formatNumber(row.meanB),
                        formatNumber(row.meanC),
                        formatCorrelation(row.withSmells),
                        formatCorrelation(row.withDefectiveness)));
            }
        }
    }

    private static void writeDetailsCsv(final Path output, final AnalysisResult result) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writeCsvRecord(writer, List.of(
                    "Variable",
                    "Mean_A",
                    "Mean_B_derived_from_B_plus",
                    "Mean_C",
                    "Spearman_rho_NSmeels",
                    "p_value_NSmeels",
                    "significant_NSmeels_alpha_0_05",
                    "N_NSmeels",
                    "Spearman_rho_Defectiveness",
                    "p_value_Defectiveness",
                    "significant_Defectiveness_alpha_0_05",
                    "N_Defectiveness"));

            for (TableRow row : result.rows) {
                writeCsvRecord(writer, List.of(
                        row.variable,
                        rawNumber(row.meanA),
                        rawNumber(row.meanB),
                        rawNumber(row.meanC),
                        rawCorrelation(row.withSmells),
                        rawPValue(row.withSmells),
                        Boolean.toString(row.withSmells.isSignificant()),
                        Integer.toString(row.withSmells.n),
                        rawCorrelation(row.withDefectiveness),
                        rawPValue(row.withDefectiveness),
                        Boolean.toString(row.withDefectiveness.isSignificant()),
                        Integer.toString(row.withDefectiveness.n)));
            }
        }
    }

    private static void writeHtml(final Path output, final AnalysisResult result) throws IOException {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"it\"><head><meta charset=\"utf-8\">")
                .append("<title>Correlazione Feature Defectiveness</title>")
                .append("<style>")
                .append("body{font-family:Arial,sans-serif;margin:36px;background:#fff;color:#111}")
                .append("h1{text-align:center;font-family:Georgia,serif;font-weight:400}")
                .append("table{border-collapse:collapse;margin:auto;min-width:1050px}")
                .append("th{background:#eceeef;padding:10px 14px;text-align:left;border:1px solid #d9dde1}")
                .append("td{padding:8px 14px;border:1px solid #e3e6e9}")
                .append("td.num{text-align:right;font-family:Consolas,monospace}")
                .append("tr:nth-child(even){background:#fafafa}")
                .append(".note{max-width:1050px;margin:18px auto;color:#444}")
                .append("</style></head><body>")
                .append("<h1>Correlazione Feature Defectiveness</h1>")
                .append("<table><thead><tr>")
                .append("<th>Variable</th><th>DatasetA</th><th>DatasetB</th><th>DatasetC</th>")
                .append("<th>NSmells</th><th>Defectiveness</th>")
                .append("</tr></thead><tbody>")
                .append("<tr><td>Size</td><td class=\"num\">").append(result.sizeA)
                .append("</td><td class=\"num\">").append(result.sizeBPlus)
                .append("</td><td class=\"num\">").append(result.sizeC)
                .append("</td><td></td><td></td></tr>");

        for (TableRow row : result.rows) {
            html.append("<tr><td>").append(escapeHtml(row.variable)).append("</td>")
                    .append("<td class=\"num\">").append(formatNumber(row.meanA)).append("</td>")
                    .append("<td class=\"num\">").append(formatNumber(row.meanB)).append("</td>")
                    .append("<td class=\"num\">").append(formatNumber(row.meanC)).append("</td>")
                    .append("<td class=\"num\">").append(formatCorrelation(row.withSmells)).append("</td>")
                    .append("<td class=\"num\">").append(formatCorrelation(row.withDefectiveness)).append("</td></tr>");
        }

        html.append("</tbody></table>")
                .append("<div class=\"note\"><strong>Metodo:</strong> correlazione di Spearman (rho) calcolata su A. ")
                .append("L'asterisco indica p &lt; 0.05. Dataset B e' derivato da B+ forzando NSmells a 0; ")
                .append("le altre feature restano invariate.</div>")
                .append("</body></html>");

        Files.writeString(output, html.toString(), StandardCharsets.UTF_8);
    }

    private static void writePng(final Path output, final AnalysisResult result) throws IOException {
        String[] headers = {"Variable", "DatasetA", "DatasetB+", "DatasetC", "NSmells", "Defectiveness"};
        int[] widths = {330, 175, 175, 175, 215, 235};
        int tableWidth = Arrays.stream(widths).sum();
        int margin = 55;
        int titleHeight = 86;
        int rowHeight = 35;
        int footerHeight = 75;
        int totalRows = result.rows.size() + 2;
        int width = tableWidth + margin * 2;
        int height = titleHeight + totalRows * rowHeight + footerHeight + margin;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);

            Font titleFont = new Font("Serif", Font.PLAIN, 28);
            Font headerFont = new Font("SansSerif", Font.BOLD, 16);
            Font bodyFont = new Font("Monospaced", Font.PLAIN, 15);
            Font noteFont = new Font("SansSerif", Font.PLAIN, 13);

            graphics.setFont(titleFont);
            graphics.setColor(new Color(28, 28, 28));
            drawCentered(graphics, "Correlazione Feature Defectiveness", width / 2, 48);

            int xStart = margin;
            int y = titleHeight;
            graphics.setColor(new Color(236, 238, 240));
            graphics.fillRect(xStart, y, tableWidth, rowHeight);
            graphics.setColor(new Color(213, 217, 221));
            graphics.setStroke(new BasicStroke(1.0f));

            int x = xStart;
            graphics.setFont(headerFont);
            graphics.setColor(new Color(20, 20, 20));
            for (int column = 0; column < headers.length; column++) {
                graphics.drawRect(x, y, widths[column], rowHeight);
                graphics.drawString(headers[column], x + 12, y + 23);
                x += widths[column];
            }

            List<String[]> displayRows = new ArrayList<>();
            displayRows.add(new String[]{
                    "Size",
                    Integer.toString(result.sizeA),
                    Integer.toString(result.sizeBPlus),
                    Integer.toString(result.sizeC),
                    "",
                    ""});
            for (TableRow row : result.rows) {
                displayRows.add(new String[]{
                        row.variable,
                        formatNumber(row.meanA),
                        formatNumber(row.meanB),
                        formatNumber(row.meanC),
                        formatCorrelation(row.withSmells),
                        formatCorrelation(row.withDefectiveness)});
            }

            graphics.setFont(bodyFont);
            FontMetrics bodyMetrics = graphics.getFontMetrics();
            for (int rowIndex = 0; rowIndex < displayRows.size(); rowIndex++) {
                y += rowHeight;
                if (rowIndex % 2 == 1) {
                    graphics.setColor(new Color(250, 250, 250));
                    graphics.fillRect(xStart, y, tableWidth, rowHeight);
                }
                x = xStart;
                String[] values = displayRows.get(rowIndex);
                for (int column = 0; column < values.length; column++) {
                    graphics.setColor(new Color(224, 227, 230));
                    graphics.drawRect(x, y, widths[column], rowHeight);
                    graphics.setColor(new Color(22, 22, 22));
                    String value = values[column];
                    if (column == 0) {
                        graphics.drawString(value, x + 12, y + 23);
                    } else {
                        int textWidth = bodyMetrics.stringWidth(value);
                        graphics.drawString(value, x + widths[column] - textWidth - 12, y + 23);
                    }
                    x += widths[column];
                }
            }

            y += rowHeight + 30;
            graphics.setFont(noteFont);
            graphics.setColor(new Color(65, 65, 65));
            graphics.drawString("Metodo: correlazione di Spearman (rho) calcolata sul dataset A.", margin, y);
            graphics.drawString("* significativo con p < 0.05. Dataset B derivato da B+ ponendo NSmells = 0.", margin, y + 22);

            ImageIO.write(image, "png", output.toFile());
        } finally {
            graphics.dispose();
        }
    }

    private static void writeReport(
            final Path output,
            final Path aPath,
            final Path bPlusPath,
            final Path cPath,
            final AnalysisResult result) throws IOException {

        StringBuilder report = new StringBuilder();
        report.append("MILESTONE 3 - CORRELAZIONE FEATURE / DEFECTIVENESS\n\n")
                .append("Input A: ").append(aPath.toAbsolutePath().normalize()).append('\n')
                .append("Input B+: ").append(bPlusPath.toAbsolutePath().normalize()).append('\n')
                .append("Input C: ").append(cPath.toAbsolutePath().normalize()).append("\n\n")
                .append("Righe A: ").append(result.sizeA).append('\n')
                .append("Righe B+: ").append(result.sizeBPlus).append('\n')
                .append("Righe C: ").append(result.sizeC).append("\n\n")
                .append("Colonna smells: ").append(result.nSmellsColumn).append('\n')
                .append("Colonna target: ").append(result.targetColumn).append("\n\n")
                .append("Metodo:\n")
                .append("- media di ogni feature numerica su A, B e C;\n")
                .append("- B e' costruito virtualmente da B+ con NSmells = 0;\n")
                .append("- correlazione di Spearman (rho) tra ogni feature e NSmells su A;\n")
                .append("- correlazione di Spearman (rho) tra ogni feature e Defectiveness su A;\n")
                .append("- * quando il p-value bilaterale e' inferiore a 0.05;\n")
                .append("- p-value ottenuto tramite l'approssimazione t della correlazione.\n\n")
                .append("Feature analizzate: ").append(result.rows.size()).append("\n");

        for (TableRow row : result.rows) {
            report.append("- ").append(row.variable).append('\n');
        }

        report.append("\nColonne escluse:\n");
        if (result.excludedColumns.isEmpty()) {
            report.append("- nessuna\n");
        } else {
            for (String excluded : result.excludedColumns) {
                report.append("- ").append(excluded).append('\n');
            }
        }

        if (!result.warnings.isEmpty()) {
            report.append("\nAvvisi:\n");
            for (String warning : result.warnings) {
                report.append("- ").append(warning).append('\n');
            }
        }

        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
    }

    private static String formatCorrelation(final CorrelationResult result) {
        if (result.self) {
            return "-";
        }
        if (!Double.isFinite(result.rho)) {
            return "NA";
        }
        return formatNumber(result.rho) + (result.isSignificant() ? "*" : "");
    }

    private static String rawCorrelation(final CorrelationResult result) {
        if (result.self || !Double.isFinite(result.rho)) {
            return "";
        }
        return Double.toString(result.rho);
    }

    private static String rawPValue(final CorrelationResult result) {
        if (result.self || !Double.isFinite(result.pValue)) {
            return "";
        }
        return P_VALUE_FORMAT.format(result.pValue);
    }

    private static String formatNumber(final double value) {
        return Double.isFinite(value) ? NUMBER_FORMAT.format(value) : "NA";
    }

    private static String rawNumber(final double value) {
        return Double.isFinite(value) ? Double.toString(value) : "";
    }

    private static double mean(final double[] values) {
        double sum = 0.0;
        int count = 0;
        for (double value : values) {
            if (Double.isFinite(value)) {
                sum += value;
                count++;
            }
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    private static double clamp(final double value, final double minimum, final double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static Path findA(final Path milestone3Output, final Path repositoryRoot) throws IOException {
        Path inMilestone3 = findFileIgnoreCaseOrNull(milestone3Output, List.of("A.csv"));
        if (inMilestone3 != null) {
            return inMilestone3;
        }
        return findFileIgnoreCase(
                repositoryRoot.resolve("milestone-1").resolve("output"),
                List.of("A.csv"));
    }

    private static Path findFileIgnoreCase(final Path directory, final List<String> expectedNames)
            throws IOException {
        Path result = findFileIgnoreCaseOrNull(directory, expectedNames);
        if (result == null) {
            throw new IllegalStateException(
                    "Nessuno dei file " + expectedNames + " e' stato trovato nella cartella: " + directory);
        }
        return result;
    }

    private static Path findFileIgnoreCaseOrNull(final Path directory, final List<String> expectedNames)
            throws IOException {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        Set<String> normalizedNames = new LinkedHashSet<>();
        for (String expectedName : expectedNames) {
            normalizedNames.add(expectedName.toLowerCase(Locale.ROOT));
        }
        try (var files = Files.list(directory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> normalizedNames.contains(
                            path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static Path locateRepositoryRoot() throws URISyntaxException {
        Set<Path> startingPoints = new LinkedHashSet<>();
        startingPoints.add(Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize());

        Path codeLocation = Paths.get(GeneratePaperCorrelationTable.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
        startingPoints.add(Files.isDirectory(codeLocation) ? codeLocation : codeLocation.getParent());

        for (Path startingPoint : startingPoints) {
            for (Path current = startingPoint; current != null; current = current.getParent()) {
                if (Files.isDirectory(current.resolve("milestone3"))
                        && Files.isDirectory(current.resolve("milestone-1"))) {
                    return current;
                }

                Path nestedProject = current.resolve("BuggyPredictionSuite");
                if (Files.isDirectory(nestedProject.resolve("milestone3"))
                        && Files.isDirectory(nestedProject.resolve("milestone-1"))) {
                    return nestedProject;
                }

                Path name = current.getFileName();
                if (name != null
                        && name.toString().equalsIgnoreCase("milestone3")
                        && current.getParent() != null) {
                    return current.getParent();
                }
            }
        }

        throw new IllegalStateException(
                "Non trovo la root del progetto. Deve contenere le cartelle 'milestone3' e 'milestone-1'. "
                        + "Working directory IntelliJ: " + System.getProperty("user.dir"));
    }

    private static String findRequiredColumn(
            final List<String> header,
            final List<String> aliases,
            final String logicalName) {
        for (String column : header) {
            String normalized = normalizeName(column);
            for (String alias : aliases) {
                if (normalized.equals(normalizeName(alias))) {
                    return column;
                }
            }
        }
        throw new IllegalArgumentException(
                "Colonna obbligatoria " + logicalName + " non trovata. Header disponibile: " + header);
    }

    private static Map<String, String> normalizedHeaderMap(final List<String> header) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String column : header) {
            result.put(normalizeName(column), column);
        }
        return result;
    }

    private static String normalizeName(final String value) {
        return value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String escapeHtml(final String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static void writeCsvRecord(final BufferedWriter writer, final List<String> fields)
            throws IOException {
        for (int index = 0; index < fields.size(); index++) {
            if (index > 0) {
                writer.write(',');
            }
            writer.write(escapeCsv(fields.get(index)));
        }
        writer.newLine();
    }

    private static String escapeCsv(final String value) {
        String safe = value == null ? "" : value;
        if (safe.indexOf(',') >= 0 || safe.indexOf('"') >= 0
                || safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0) {
            return '"' + safe.replace("\"", "\"\"") + '"';
        }
        return safe;
    }

    private static void drawCentered(
            final Graphics2D graphics,
            final String text,
            final int centerX,
            final int baselineY) {
        FontMetrics metrics = graphics.getFontMetrics();
        graphics.drawString(text, centerX - metrics.stringWidth(text) / 2, baselineY);
    }

    private static void showDialog(final String title, final String message, final int messageType) {
        try {
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(null, message, title, messageType);
            }
        } catch (Throwable ignored) {
            // La console di IntelliJ contiene comunque lo stesso messaggio.
        }
    }

    private static final class Dataset {
        private final List<String> header;
        private final List<Map<String, String>> rows;
        private final Map<String, String> normalizedToOriginal;

        private Dataset(final List<String> header, final List<Map<String, String>> rows) {
            this.header = List.copyOf(header);
            this.rows = List.copyOf(rows);
            this.normalizedToOriginal = normalizedHeaderMap(header);
        }

        private static Dataset read(final Path path) throws IOException {
            char delimiter = detectDelimiter(path);
            try (CsvReader reader = new CsvReader(path, delimiter)) {
                List<String> header = requireHeader(reader, path);
                List<Map<String, String>> rows = new ArrayList<>();

                List<String> record;
                long recordNumber = 1;
                while ((record = reader.nextRecord()) != null) {
                    recordNumber++;
                    if (isEmptyRecord(record)) {
                        continue;
                    }
                    if (record.size() != header.size()) {
                        throw new IllegalArgumentException(
                                "CSV " + path.getFileName() + ", record " + recordNumber
                                        + ": attese " + header.size() + " colonne, trovate " + record.size() + '.');
                    }
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int index = 0; index < header.size(); index++) {
                        row.put(header.get(index), record.get(index));
                    }
                    rows.add(row);
                }

                if (rows.isEmpty()) {
                    throw new IllegalArgumentException("Il CSV non contiene righe dati: " + path);
                }
                return new Dataset(header, rows);
            }
        }

        private boolean isNumericColumn(final String requestedColumn) {
            String column = resolveColumn(requestedColumn);
            int nonMissing = 0;
            int numeric = 0;
            for (Map<String, String> row : rows) {
                String raw = row.get(column);
                if (isMissing(raw)) {
                    continue;
                }
                nonMissing++;
                if (tryParseDouble(raw) != null) {
                    numeric++;
                }
            }
            return nonMissing > 0 && numeric == nonMissing;
        }

        private double[] numericColumn(final String requestedColumn) {
            String column = resolveColumn(requestedColumn);
            double[] values = new double[rows.size()];
            for (int index = 0; index < rows.size(); index++) {
                String raw = rows.get(index).get(column);
                if (isMissing(raw)) {
                    values[index] = Double.NaN;
                    continue;
                }
                Double parsed = tryParseDouble(raw);
                if (parsed == null) {
                    throw new IllegalArgumentException(
                            "Valore non numerico nella colonna '" + column + "': '" + raw + "'.");
                }
                values[index] = parsed;
            }
            return values;
        }

        private double[] binaryTargetColumn(final String requestedColumn) {
            String column = resolveColumn(requestedColumn);
            List<String> distinct = new ArrayList<>();
            for (Map<String, String> row : rows) {
                String raw = row.get(column);
                if (!isMissing(raw)) {
                    String normalized = raw.trim().toLowerCase(Locale.ROOT);
                    if (!distinct.contains(normalized)) {
                        distinct.add(normalized);
                    }
                }
            }

            if (distinct.size() != 2) {
                throw new IllegalArgumentException(
                        "La colonna target '" + column + "' deve essere binaria. Valori trovati: " + distinct);
            }

            Map<String, Double> mapping = inferBinaryMapping(distinct);
            double[] values = new double[rows.size()];
            for (int index = 0; index < rows.size(); index++) {
                String raw = rows.get(index).get(column);
                if (isMissing(raw)) {
                    values[index] = Double.NaN;
                } else {
                    String normalized = raw.trim().toLowerCase(Locale.ROOT);
                    Double mapped = mapping.get(normalized);
                    if (mapped == null) {
                        throw new IllegalArgumentException(
                                "Valore target non riconosciuto in '" + column + "': '" + raw + "'.");
                    }
                    values[index] = mapped;
                }
            }
            return values;
        }

        private String resolveColumn(final String requestedColumn) {
            String resolved = normalizedToOriginal.get(normalizeName(requestedColumn));
            if (resolved == null) {
                throw new IllegalArgumentException(
                        "Colonna '" + requestedColumn + "' non trovata. Header: " + header);
            }
            return resolved;
        }
    }

    private static Map<String, Double> inferBinaryMapping(final List<String> distinctValues) {
        Map<String, Double> mapping = new HashMap<>();
        for (String value : distinctValues) {
            if (isPositiveLabel(value)) {
                mapping.put(value, 1.0);
            } else if (isNegativeLabel(value)) {
                mapping.put(value, 0.0);
            }
        }

        if (mapping.size() == 2 && new LinkedHashSet<>(mapping.values()).size() == 2) {
            return mapping;
        }

        Double firstNumber = tryParseDouble(distinctValues.get(0));
        Double secondNumber = tryParseDouble(distinctValues.get(1));
        if (firstNumber != null && secondNumber != null && !firstNumber.equals(secondNumber)) {
            String lowerLabel = firstNumber < secondNumber ? distinctValues.get(0) : distinctValues.get(1);
            String higherLabel = firstNumber < secondNumber ? distinctValues.get(1) : distinctValues.get(0);
            mapping.clear();
            mapping.put(lowerLabel, 0.0);
            mapping.put(higherLabel, 1.0);
            return mapping;
        }

        throw new IllegalArgumentException(
                "Non riesco a stabilire quale valore della classe sia positivo. Valori target: "
                        + distinctValues + ". Sono supportati yes/no, true/false, buggy/clean, defective/non-defective e 0/1.");
    }

    private static boolean isPositiveLabel(final String raw) {
        String value = normalizeName(raw);
        return Set.of("yes", "true", "1", "buggy", "defective", "positive", "y", "si")
                .contains(value);
    }

    private static boolean isNegativeLabel(final String raw) {
        String value = normalizeName(raw);
        return Set.of(
                "no", "false", "0", "clean", "nonbuggy", "notbuggy",
                "nondefective", "notdefective", "negative", "n")
                .contains(value);
    }

    private static Double tryParseDouble(final String rawValue) {
        if (isMissing(rawValue)) {
            return null;
        }
        String value = rawValue.trim();
        if (value.indexOf(',') >= 0 && value.indexOf('.') < 0) {
            value = value.replace(',', '.');
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isMissing(final String rawValue) {
        if (rawValue == null) {
            return true;
        }
        String value = rawValue.trim().toLowerCase(Locale.ROOT);
        return value.isEmpty()
                || value.equals("?")
                || value.equals("null")
                || value.equals("na")
                || value.equals("n/a")
                || value.equals("nan");
    }

    private static char detectDelimiter(final Path input) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IllegalArgumentException("Il CSV e' vuoto: " + input);
            }

            char[] candidates = {',', ';', '\t'};
            char best = ',';
            int bestCount = -1;
            for (char candidate : candidates) {
                int count = countOutsideQuotes(headerLine, candidate);
                if (count > bestCount) {
                    bestCount = count;
                    best = candidate;
                }
            }
            if (bestCount <= 0) {
                throw new IllegalArgumentException(
                        "Separatore CSV non riconosciuto in " + input
                                + ". Sono supportati virgola, punto e virgola e tab.");
            }
            return best;
        }
    }

    private static int countOutsideQuotes(final String line, final char delimiter) {
        int count = 0;
        boolean insideQuotes = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (insideQuotes && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    index++;
                } else {
                    insideQuotes = !insideQuotes;
                }
            } else if (!insideQuotes && character == delimiter) {
                count++;
            }
        }
        return count;
    }

    private static List<String> requireHeader(final CsvReader reader, final Path path) throws IOException {
        List<String> header;
        do {
            header = reader.nextRecord();
        } while (header != null && isEmptyRecord(header));

        if (header == null) {
            throw new IllegalArgumentException("Il CSV non contiene un header: " + path);
        }
        if (!header.isEmpty() && header.get(0).startsWith("\uFEFF")) {
            header.set(0, header.get(0).substring(1));
        }
        return header;
    }

    private static boolean isEmptyRecord(final List<String> record) {
        return record.stream().allMatch(String::isBlank);
    }

    private static final class CsvReader implements AutoCloseable {
        private final BufferedReader reader;
        private final char delimiter;
        private int pendingCharacter = -1;
        private boolean endOfFile;

        private CsvReader(final Path path, final char delimiter) throws IOException {
            this.reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
            this.delimiter = delimiter;
        }

        private List<String> nextRecord() throws IOException {
            if (endOfFile) {
                return null;
            }

            List<String> record = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean inQuotes = false;
            boolean sawAnyCharacter = false;

            while (true) {
                int read = nextCharacter();
                if (read == -1) {
                    endOfFile = true;
                    if (!sawAnyCharacter && record.isEmpty() && field.isEmpty()) {
                        return null;
                    }
                    record.add(field.toString());
                    return record;
                }

                sawAnyCharacter = true;
                char character = (char) read;

                if (inQuotes) {
                    if (character == '"') {
                        int following = nextCharacter();
                        if (following == '"') {
                            field.append('"');
                        } else {
                            inQuotes = false;
                            pendingCharacter = following;
                        }
                    } else {
                        field.append(character);
                    }
                    continue;
                }

                if (character == '"' && field.isEmpty()) {
                    inQuotes = true;
                } else if (character == delimiter) {
                    record.add(field.toString());
                    field.setLength(0);
                } else if (character == '\n') {
                    record.add(stripTrailingCarriageReturn(field.toString()));
                    return record;
                } else if (character == '\r') {
                    int following = nextCharacter();
                    if (following != '\n') {
                        pendingCharacter = following;
                    }
                    record.add(field.toString());
                    return record;
                } else {
                    field.append(character);
                }
            }
        }

        private int nextCharacter() throws IOException {
            if (pendingCharacter != -1) {
                int result = pendingCharacter;
                pendingCharacter = -1;
                return result;
            }
            return reader.read();
        }

        private String stripTrailingCarriageReturn(final String value) {
            return value.endsWith("\r") ? value.substring(0, value.length() - 1) : value;
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    private static final class CorrelationResult {
        private final double rho;
        private final double pValue;
        private final int n;
        private final boolean self;

        private CorrelationResult(
                final double rho,
                final double pValue,
                final int n,
                final boolean self) {
            this.rho = rho;
            this.pValue = pValue;
            this.n = n;
            this.self = self;
        }

        private static CorrelationResult undefined(final int n) {
            return new CorrelationResult(Double.NaN, Double.NaN, n, false);
        }

        private static CorrelationResult selfCorrelation() {
            return new CorrelationResult(Double.NaN, Double.NaN, 0, true);
        }

        private boolean isSignificant() {
            return !self && Double.isFinite(pValue) && pValue < ALPHA;
        }
    }

    private static final class TableRow {
        private final String variable;
        private final double meanA;
        private final double meanB;
        private final double meanC;
        private final CorrelationResult withSmells;
        private final CorrelationResult withDefectiveness;

        private TableRow(
                final String variable,
                final double meanA,
                final double meanB,
                final double meanC,
                final CorrelationResult withSmells,
                final CorrelationResult withDefectiveness) {
            this.variable = variable;
            this.meanA = meanA;
            this.meanB = meanB;
            this.meanC = meanC;
            this.withSmells = withSmells;
            this.withDefectiveness = withDefectiveness;
        }
    }

    private static final class AnalysisResult {
        private final int sizeA;
        private final int sizeBPlus;
        private final int sizeC;
        private final String nSmellsColumn;
        private final String targetColumn;
        private final List<TableRow> rows;
        private final List<String> excludedColumns;
        private final List<String> warnings;

        private AnalysisResult(
                final int sizeA,
                final int sizeBPlus,
                final int sizeC,
                final String nSmellsColumn,
                final String targetColumn,
                final List<TableRow> rows,
                final List<String> excludedColumns,
                final List<String> warnings) {
            this.sizeA = sizeA;
            this.sizeBPlus = sizeBPlus;
            this.sizeC = sizeC;
            this.nSmellsColumn = nSmellsColumn;
            this.targetColumn = targetColumn;
            this.rows = List.copyOf(rows);
            this.excludedColumns = List.copyOf(excludedColumns);
            this.warnings = List.copyOf(warnings);
        }
    }
}
