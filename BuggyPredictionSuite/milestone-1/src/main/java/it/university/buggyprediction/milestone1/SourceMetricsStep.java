package it.university.buggyprediction.milestone1;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.LanguageRegistry;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Produces the Dataset feature rows.
 *
 * <p>Product metrics are calculated on the source snapshot of the current
 * release. Process metrics are calculated from Git history reachable from the
 * release tag. PMD features are copied from the same file path in the previous
 * selected release, so the prediction row does not use smell information from
 * the release whose bugginess is being predicted.</p>
 */
final class SourceMetricsStep implements MilestoneStep {

    private static final Logger LOGGER =
            Logger.getLogger(SourceMetricsStep.class.getName());

    private final GitHubService gitHubService;

    SourceMetricsStep(final GitHubService gitHubService) {
        this.gitHubService = gitHubService;
    }

    @Override
    public String id() {
        return "metrics";
    }

    @Override
    public String description() {
        return "Recupero sorgenti e calcolo metriche di prodotto, processo e PMD";
    }

    @Override
    public void execute(final PipelineContext context) throws Exception {
        List<Release> selected = context.requireReleaseCatalog().selectFirstFraction(
                context.config().releaseFraction(),
                context.config().maxSelectedReleases());
        if (selected.isEmpty()) {
            throw new IllegalStateException("Nessuna release con tag Git selezionata.");
        }
        context.selectedReleases(selected);

        Set<String> validatedFixCommitHashes = context.requireTickets().stream()
                .flatMap(ticket -> ticket.validCommits.stream())
                .map(commit -> commit.hash)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        SourceMetricsAnalyzer analyzer = new SourceMetricsAnalyzer();
        List<ClassMetrics> rows = new ArrayList<>();
        Map<String, PmdSmellSnapshot> previousReleaseSmells = Map.of();
        String previousReleaseName = "";

        for (int index = 0; index < selected.size(); index++) {
            Release release = selected.get(index);
            int current = index + 1;
            LOGGER.info(() -> "Release " + current + "/" + selected.size()
                    + ": " + release.version);

            Map<String, FileProcessMetrics> processMetrics =
                    gitHubService.collectFileProcessMetrics(
                            release,
                            validatedFixCommitHashes);

            List<ClassMetrics> releaseRows;
            try (SourceSnapshot snapshot = gitHubService.createSnapshot(release)) {
                releaseRows = analyzer.analyze(snapshot.repositoryRoot(), release);
            }

            for (ClassMetrics row : releaseRows) {
                row.applyProcessMetrics(processMetrics.get(row.classPath));
                row.applyPreviousSmells(
                        previousReleaseName,
                        previousReleaseSmells.get(row.classPath));
            }

            previousReleaseSmells = releaseRows.stream().collect(Collectors.toMap(
                    row -> row.classPath,
                    ClassMetrics::currentSmells,
                    (first, second) -> first,
                    LinkedHashMap::new));
            previousReleaseName = release.version;

            release.productionJavaFileCount = releaseRows.size();
            rows.addAll(releaseRows);

            long pmdErrors = releaseRows.stream()
                    .map(ClassMetrics::currentSmells)
                    .filter(smells -> PmdSmellSnapshot.STATUS_ERROR.equals(smells.status()))
                    .count();
            LOGGER.log(
                    Level.INFO,
                    "Release {0}: {1} file Java, {2} file con storia Git, {3} errori PMD",
                    new Object[]{
                            release.version,
                            releaseRows.size(),
                            processMetrics.size(),
                            pmdErrors
                    });
        }

        context.datasetRows(rows);
        LOGGER.log(Level.INFO, "Classi/file Java analizzati: {0}", rows.size());
    }
}


/**
 * Calculates LOC, CLOC, WMC and NPM with JavaParser, and delegates smell
 * detection to PMD using the fixed project ruleset.
 */
final class SourceMetricsAnalyzer {

    private final JavaParser parser;
    private final SourceProductMetricsCalculator productCalculator =
            new SourceProductMetricsCalculator();
    private final PmdSmellAnalyzer pmdAnalyzer = new PmdSmellAnalyzer();

    SourceMetricsAnalyzer() {
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                .setCharacterEncoding(StandardCharsets.UTF_8);
        parser = new JavaParser(configuration);
    }

    List<ClassMetrics> analyze(final Path sourceRoot, final Release release)
            throws IOException {
        List<Path> javaFiles = collectProductionJavaFiles(sourceRoot);
        Map<String, PmdSmellSnapshot> pmdResults =
                pmdAnalyzer.analyze(sourceRoot, javaFiles);

        List<ClassMetrics> result = new ArrayList<>(javaFiles.size());
        for (Path javaFile : javaFiles) {
            String relativePath = relativePath(sourceRoot, javaFile);
            PmdSmellSnapshot pmdSnapshot = pmdResults.getOrDefault(
                    relativePath,
                    PmdSmellSnapshot.error("PMD_RESULT_NOT_FOUND"));
            result.add(parseFile(sourceRoot, javaFile)
                    .toClassMetrics(release, pmdSnapshot));
        }
        result.sort(Comparator.comparing(metrics -> metrics.classPath));
        return result;
    }

    private static List<Path> collectProductionJavaFiles(final Path sourceRoot)
            throws IOException {
        try (var stream = Files.walk(sourceRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> ProductionJavaPathFilter.isProductionJavaPath(
                            sourceRoot.relativize(path).toString()))
                    .sorted()
                    .toList();
        }
    }

    private SourceFileModel parseFile(
            final Path sourceRoot,
            final Path javaFile) throws IOException {
        String relativePath = relativePath(sourceRoot, javaFile);
        String source = Files.readString(javaFile, StandardCharsets.UTF_8);
        LineCounts lineCounts = LineCounts.count(source);

        ParseResult<CompilationUnit> parseResult = parser.parse(source);
        CompilationUnit unit = parseResult.getResult().orElse(null);
        String className = stripJavaExtension(javaFile.getFileName().toString());

        if (unit == null) {
            return SourceFileModel.unparsed(
                    relativePath,
                    className,
                    lineCounts,
                    parseResult.getProblems().stream()
                            .map(Object::toString)
                            .collect(Collectors.joining(" | ")));
        }

        List<TypeDeclaration<?>> topLevelTypes = unit.getTypes();
        if (!topLevelTypes.isEmpty()) {
            className = topLevelTypes.getFirst().getNameAsString();
        }

        SourceFileModel model = new SourceFileModel(
                relativePath,
                className,
                lineCounts,
                unit,
                "");
        productCalculator.calculate(model);
        return model;
    }

    private static String relativePath(final Path sourceRoot, final Path javaFile) {
        return sourceRoot.toAbsolutePath().normalize()
                .relativize(javaFile.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    private static String stripJavaExtension(final String filename) {
        return filename.endsWith(".java")
                ? filename.substring(0, filename.length() - 5)
                : filename;
    }
}


/**
 * Executes PMD once per release snapshot and groups its violations by source
 * file. Only the rules included in {@code pmd/milestone1-smells.xml} contribute
 * to the dataset.
 */
final class PmdSmellAnalyzer {

    static final String PMD_VERSION = "7.26.0";
    static final String RULESET_RESOURCE = "pmd/milestone1-smells.xml";
    static final String JAVA_LANGUAGE_VERSION = "1.8";

    private static final Logger LOGGER =
            Logger.getLogger(PmdSmellAnalyzer.class.getName());

    Map<String, PmdSmellSnapshot> analyze(
            final Path sourceRoot,
            final List<Path> javaFiles) throws IOException {
        Map<String, PmdSmellSnapshot> results = new LinkedHashMap<>();
        Map<Path, String> relativeByAbsolutePath = new LinkedHashMap<>();

        for (Path file : javaFiles) {
            Path absolute = file.toAbsolutePath().normalize();
            String relative = sourceRoot.toAbsolutePath().normalize()
                    .relativize(absolute)
                    .toString()
                    .replace('\\', '/');
            relativeByAbsolutePath.put(absolute, relative);
            results.put(relative, PmdSmellSnapshot.success(Map.of()));
        }

        if (javaFiles.isEmpty()) {
            return results;
        }

        Path reportFile = Files.createTempFile("milestone1-pmd-", ".xml");
        try {
            runPmd(sourceRoot, javaFiles, reportFile);
            parseReport(sourceRoot, reportFile, relativeByAbsolutePath, results);
            return results;
        } finally {
            Files.deleteIfExists(reportFile);
        }
    }

    private static void runPmd(
            final Path sourceRoot,
            final List<Path> javaFiles,
            final Path reportFile) {
        PMDConfiguration configuration = new PMDConfiguration();
        configuration.setDefaultLanguageVersion(
                LanguageRegistry.PMD.getLanguageById("java")
                        .getVersion(JAVA_LANGUAGE_VERSION));
        configuration.setSourceEncoding(StandardCharsets.UTF_8);
        configuration.addRuleSet(RULESET_RESOURCE);
        configuration.setReportFormat("xml");
        configuration.setReportFile(reportFile);
        configuration.addRelativizeRoot(sourceRoot.toAbsolutePath().normalize());

        try (PmdAnalysis analysis = PmdAnalysis.create(configuration)) {
            for (Path javaFile : javaFiles) {
                analysis.files().addFile(javaFile);
            }
            analysis.performAnalysis();
        }
    }

    private static void parseReport(
            final Path sourceRoot,
            final Path reportFile,
            final Map<Path, String> relativeByAbsolutePath,
            final Map<String, PmdSmellSnapshot> results) throws IOException {
        Document document = parseXml(reportFile);
        failOnConfigurationErrors(document);

        NodeList files = document.getElementsByTagName("file");
        for (int index = 0; index < files.getLength(); index++) {
            Element fileElement = (Element) files.item(index);
            String path = resolveReportedPath(
                    sourceRoot,
                    fileElement.getAttribute("name"),
                    relativeByAbsolutePath);
            if (path == null) {
                LOGGER.warning(() -> "PMD ha riportato una path non riconosciuta: "
                        + fileElement.getAttribute("name"));
                continue;
            }

            Map<String, Integer> ruleCounts = new TreeMap<>();
            NodeList violations = fileElement.getElementsByTagName("violation");
            for (int violationIndex = 0;
                    violationIndex < violations.getLength();
                    violationIndex++) {
                Element violation = (Element) violations.item(violationIndex);
                String rule = violation.getAttribute("rule").trim();
                if (!rule.isEmpty()) {
                    ruleCounts.merge(rule, 1, Integer::sum);
                }
            }
            results.put(path, PmdSmellSnapshot.success(ruleCounts));
        }

        NodeList errors = document.getElementsByTagName("error");
        for (int index = 0; index < errors.getLength(); index++) {
            Element error = (Element) errors.item(index);
            String path = resolveReportedPath(
                    sourceRoot,
                    error.getAttribute("filename"),
                    relativeByAbsolutePath);
            String message = error.getAttribute("msg").trim();
            if (path == null) {
                LOGGER.warning(() -> "Errore PMD non associabile a una path: " + message);
                continue;
            }
            results.put(path, PmdSmellSnapshot.error(message));
        }
    }

    private static Document parseXml(final Path reportFile) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(reportFile.toFile());
        } catch (Exception exception) {
            throw new IOException("Impossibile leggere il report XML di PMD.", exception);
        }
    }

    private static void failOnConfigurationErrors(final Document document) {
        NodeList errors = document.getElementsByTagName("configerror");
        if (errors.getLength() == 0) {
            return;
        }

        List<String> messages = new ArrayList<>();
        for (int index = 0; index < errors.getLength(); index++) {
            Element error = (Element) errors.item(index);
            messages.add(error.getAttribute("rule") + ": "
                    + error.getAttribute("msg"));
        }
        throw new IllegalStateException(
                "Configurazione PMD non valida: " + String.join(" | ", messages));
    }

    private static String resolveReportedPath(
            final Path sourceRoot,
            final String reportedPath,
            final Map<Path, String> relativeByAbsolutePath) {
        if (reportedPath == null || reportedPath.isBlank()) {
            return null;
        }

        String normalizedText = reportedPath.replace('\\', '/');
        Path reported = Path.of(reportedPath);
        Path absolute = reported.isAbsolute()
                ? reported.toAbsolutePath().normalize()
                : sourceRoot.resolve(reported).toAbsolutePath().normalize();

        String exact = relativeByAbsolutePath.get(absolute);
        if (exact != null) {
            return exact;
        }

        String normalizedRelative = normalizedText.replaceFirst("^\\./", "");
        if (relativeByAbsolutePath.containsValue(normalizedRelative)) {
            return normalizedRelative;
        }

        return relativeByAbsolutePath.entrySet().stream()
                .filter(entry -> entry.getKey().toString()
                        .replace('\\', '/')
                        .endsWith("/" + normalizedRelative))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}


final class SourceFileModel {

    final String path;
    final String className;
    final LineCounts lineCounts;
    final CompilationUnit compilationUnit;
    final String parseWarning;

    int publicMethods;
    int wmc;

    SourceFileModel(
            final String path,
            final String className,
            final LineCounts lineCounts,
            final CompilationUnit compilationUnit,
            final String parseWarning) {
        this.path = path;
        this.className = className;
        this.lineCounts = lineCounts;
        this.compilationUnit = compilationUnit;
        this.parseWarning = parseWarning;
    }

    static SourceFileModel unparsed(
            final String path,
            final String className,
            final LineCounts lineCounts,
            final String parseWarning) {
        return new SourceFileModel(path, className, lineCounts, null, parseWarning);
    }

    ClassMetrics toClassMetrics(
            final Release release,
            final PmdSmellSnapshot pmdSnapshot) {
        ClassMetrics metrics = new ClassMetrics();
        metrics.project = Milestone1Constants.PROJECT_NAME;
        metrics.release = release;
        metrics.className = className;
        metrics.classPath = path;
        metrics.loc = lineCounts.loc;
        metrics.cloc = lineCounts.cloc;
        metrics.wmc = wmc;
        metrics.npm = publicMethods;
        metrics.detectedPmdSmells = pmdSnapshot;
        metrics.analysisWarning = parseWarning;
        return metrics;
    }
}


/** Calculates only the source product metrics exposed as dataset features. */
final class SourceProductMetricsCalculator {

    void calculate(final SourceFileModel model) {
        if (model.compilationUnit == null) {
            return;
        }

        List<CallableDeclaration<?>> callables = new ArrayList<>();
        callables.addAll(model.compilationUnit.findAll(MethodDeclaration.class));
        callables.addAll(model.compilationUnit.findAll(ConstructorDeclaration.class));

        for (CallableDeclaration<?> callable : callables) {
            model.wmc += cyclomaticComplexity(callable);
            if (callable instanceof MethodDeclaration declaration
                    && declaration.isPublic()) {
                model.publicMethods++;
            }
        }
    }

    private static int cyclomaticComplexity(
            final CallableDeclaration<?> callable) {
        return 1
                + callable.findAll(IfStmt.class).size()
                + callable.findAll(ForStmt.class).size()
                + callable.findAll(ForEachStmt.class).size()
                + callable.findAll(WhileStmt.class).size()
                + callable.findAll(DoStmt.class).size()
                + callable.findAll(CatchClause.class).size()
                + callable.findAll(ConditionalExpr.class).size()
                + callable.findAll(SwitchEntry.class).size()
                + (int) callable.findAll(BinaryExpr.class).stream()
                        .filter(expression -> expression.getOperator()
                                == BinaryExpr.Operator.AND
                                || expression.getOperator()
                                == BinaryExpr.Operator.OR)
                        .count();
    }
}


final class LineCounts {

    final int loc;
    final int cloc;

    LineCounts(final int loc, final int cloc) {
        this.loc = loc;
        this.cloc = cloc;
    }

    static LineCounts count(final String source) {
        int loc = 0;
        int cloc = 0;
        boolean insideBlockComment = false;

        for (String rawLine : source.split("\\R", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            boolean containsCode = false;
            boolean containsComment = false;
            int index = 0;

            while (index < line.length()) {
                if (insideBlockComment) {
                    containsComment = true;
                    int end = line.indexOf("*/", index);
                    if (end < 0) {
                        index = line.length();
                    } else {
                        insideBlockComment = false;
                        index = end + 2;
                    }
                    continue;
                }

                int lineComment = line.indexOf("//", index);
                int blockComment = line.indexOf("/*", index);
                int nextComment;
                boolean block;
                if (lineComment < 0) {
                    nextComment = blockComment;
                    block = true;
                } else if (blockComment < 0 || lineComment < blockComment) {
                    nextComment = lineComment;
                    block = false;
                } else {
                    nextComment = blockComment;
                    block = true;
                }

                if (nextComment < 0) {
                    if (!line.substring(index).isBlank()) {
                        containsCode = true;
                    }
                    break;
                }

                if (!line.substring(index, nextComment).isBlank()) {
                    containsCode = true;
                }
                containsComment = true;

                if (!block) {
                    break;
                }
                insideBlockComment = true;
                index = nextComment + 2;
            }

            if (containsCode) {
                loc++;
            }
            if (containsComment) {
                cloc++;
            }
        }
        return new LineCounts(loc, cloc);
    }
}
