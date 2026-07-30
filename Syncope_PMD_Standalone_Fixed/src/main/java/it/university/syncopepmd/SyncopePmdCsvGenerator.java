package it.university.syncopepmd;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Standalone utility that downloads the Apache Syncope fork from GitHub,
 * downloads PMD, analyzes production Java source files with maintainability rules and writes a CSV
 * ordered by descending code-smell count.
 */
public final class SyncopePmdCsvGenerator {

    private static final String DEFAULT_REPOSITORY_ZIP =
            "https://github.com/jadepics/syncope/archive/refs/heads/master.zip";
    private static final String DEFAULT_BRANCH = "master";
    private static final String PMD_VERSION = "7.26.0";
    private static final String PMD_ZIP_URL =
            "https://github.com/pmd/pmd/releases/download/"
                    + "pmd_releases%2F" + PMD_VERSION
                    + "/pmd-dist-" + PMD_VERSION + "-bin.zip";
    private static final String PMD_DIRECTORY_NAME = "pmd-bin-" + PMD_VERSION;
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;");
    private static final int BUFFER_SIZE = 64 * 1024;

    private SyncopePmdCsvGenerator() {
    }

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            if (options.help()) {
                printHelp();
                return;
            }
            run(options);
        } catch (Exception exception) {
            System.err.println();
            System.err.println("ERRORE: " + exception.getMessage());
            exception.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run(Options options) throws Exception {
        Path projectRoot = options.projectRoot().toAbsolutePath().normalize();
        Path runtimeRoot = projectRoot.resolve(options.runtimeDirectory()).normalize();
        Path downloadsRoot = runtimeRoot.resolve("downloads");
        Path repositoryRoot = runtimeRoot.resolve("repository");
        Path toolsRoot = runtimeRoot.resolve("tools");
        Path pmdRoot = toolsRoot.resolve(PMD_DIRECTORY_NAME);
        Path generatedRoot = runtimeRoot.resolve("generated");
        Path outputCsv = projectRoot.resolve(options.outputCsv()).normalize();

        Files.createDirectories(downloadsRoot);
        Files.createDirectories(toolsRoot);
        Files.createDirectories(generatedRoot);
        Files.createDirectories(outputCsv.getParent());

        System.out.println("=== Syncope PMD Code Smell CSV ===");
        System.out.println("Repository: " + options.repositoryZipUrl());
        System.out.println("Branch:     " + options.branch());
        System.out.println("Output:     " + outputCsv);
        System.out.println();

        prepareRepository(options, downloadsRoot, repositoryRoot);
        preparePmd(options, downloadsRoot, toolsRoot, pmdRoot);

        Path ruleset = generatedRoot.resolve("pmd-codesmells-ruleset.xml");
        copyClasspathResource("/pmd-codesmells-ruleset.xml", ruleset);

        List<Path> javaFiles = collectProductionJavaFiles(repositoryRoot, options.includeTests());
        if (javaFiles.isEmpty()) {
            throw new IllegalStateException("Nessun file Java da analizzare trovato in " + repositoryRoot);
        }

        System.out.printf(Locale.ROOT, "File Java selezionati: %,d%n", javaFiles.size());

        Path fileList = generatedRoot.resolve("pmd-java-files.txt");
        writeFileList(javaFiles, fileList);

        Path pmdReport = generatedRoot.resolve("pmd-report.xml");
        Files.deleteIfExists(pmdReport);
        executePmd(pmdRoot, fileList, ruleset, pmdReport, repositoryRoot, options.threads());

        Map<Path, ClassResult> results = initializeResults(repositoryRoot, javaFiles);
        ReportSummary summary = parsePmdReport(repositoryRoot, pmdReport, results);
        writeCsv(outputCsv, results.values());

        long totalSmells = results.values().stream().mapToLong(ClassResult::smellCount).sum();
        long classesWithSmells = results.values().stream().filter(result -> result.smellCount() > 0).count();
        long classesWithErrors = results.values().stream().filter(ClassResult::hasErrors).count();

        System.out.println();
        System.out.println("Analisi completata.");
        System.out.printf(Locale.ROOT, "Classi/file analizzati: %,d%n", results.size());
        System.out.printf(Locale.ROOT, "Classi con smell:       %,d%n", classesWithSmells);
        System.out.printf(Locale.ROOT, "Code smell totali:      %,d%n", totalSmells);
        System.out.printf(Locale.ROOT, "File con errori PMD:    %,d%n", classesWithErrors);
        if (!summary.configurationErrors().isEmpty()) {
            System.out.println("Avvisi di configurazione PMD ignorati: "
                    + summary.configurationErrors().size());
        }
        System.out.println("CSV creato in: " + outputCsv);
    }

    private static void prepareRepository(
            Options options,
            Path downloadsRoot,
            Path repositoryRoot) throws Exception {

        if (!options.refreshRepository() && Files.isDirectory(repositoryRoot)) {
            System.out.println("Repository locale riutilizzata: " + repositoryRoot);
            return;
        }

        System.out.println("Scaricamento del fork Syncope da GitHub...");
        Path archive = downloadsRoot.resolve("syncope-" + options.branch() + ".zip");
        download(options.repositoryZipUrl(), archive);

        Path extraction = repositoryRoot.resolveSibling("repository-extracting");
        deleteRecursively(extraction);
        deleteRecursively(repositoryRoot);
        Files.createDirectories(extraction);
        unzip(archive, extraction);

        Path extractedRoot = findSingleExtractedDirectory(extraction);
        try {
            Files.move(extractedRoot, repositoryRoot, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            Files.move(extractedRoot, repositoryRoot, StandardCopyOption.REPLACE_EXISTING);
        }
        deleteRecursively(extraction);
        System.out.println("Repository pronta: " + repositoryRoot);
    }

    private static void preparePmd(
            Options options,
            Path downloadsRoot,
            Path toolsRoot,
            Path pmdRoot) throws Exception {

        if (!options.refreshPmd() && Files.isDirectory(pmdRoot)) {
            System.out.println("PMD riutilizzato: " + pmdRoot);
            ensureUnixPmdIsExecutable(pmdRoot);
            return;
        }

        if (Files.isDirectory(pmdRoot) && !options.refreshPmd()) {
            ensureUnixPmdIsExecutable(pmdRoot);
            return;
        }

        System.out.println("Scaricamento di PMD " + PMD_VERSION + "...");
        Path archive = downloadsRoot.resolve("pmd-dist-" + PMD_VERSION + "-bin.zip");
        download(PMD_ZIP_URL, archive);

        Path extraction = toolsRoot.resolve("pmd-extracting");
        deleteRecursively(extraction);
        deleteRecursively(pmdRoot);
        Files.createDirectories(extraction);
        unzip(archive, extraction);

        Path extractedRoot = extraction.resolve(PMD_DIRECTORY_NAME);
        if (!Files.isDirectory(extractedRoot)) {
            extractedRoot = findSingleExtractedDirectory(extraction);
        }
        try {
            Files.move(extractedRoot, pmdRoot, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            Files.move(extractedRoot, pmdRoot, StandardCopyOption.REPLACE_EXISTING);
        }
        deleteRecursively(extraction);
        ensureUnixPmdIsExecutable(pmdRoot);
        System.out.println("PMD pronto: " + pmdRoot);
    }

    private static void download(String url, Path destination) throws Exception {
        Files.createDirectories(destination.getParent());
        Path partial = destination.resolveSibling(destination.getFileName() + ".part");
        Files.deleteIfExists(partial);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(20))
                .header("User-Agent", "Syncope-PMD-CSV-Generator/1.0")
                .GET()
                .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Download fallito (HTTP " + response.statusCode() + "): " + url);
        }

        long expectedLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        try (InputStream input = new BufferedInputStream(response.body(), BUFFER_SIZE);
             OutputStream output = new BufferedOutputStream(Files.newOutputStream(partial), BUFFER_SIZE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long downloaded = 0L;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                downloaded += read;
                if (expectedLength > 0 && downloaded % (10L * 1024 * 1024) < BUFFER_SIZE) {
                    double percentage = downloaded * 100.0 / expectedLength;
                    System.out.printf(Locale.ROOT, "  %.1f%%%n", percentage);
                }
            }
        } catch (Exception exception) {
            Files.deleteIfExists(partial);
            throw exception;
        }

        Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void unzip(Path archive, Path destinationRoot) throws IOException {
        Path normalizedRoot = destinationRoot.toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(archive), BUFFER_SIZE),
                StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buffer = new byte[BUFFER_SIZE];
            while ((entry = zip.getNextEntry()) != null) {
                Path destination = normalizedRoot.resolve(entry.getName()).normalize();
                if (!destination.startsWith(normalizedRoot)) {
                    throw new IOException("Archivio ZIP non sicuro: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    try (OutputStream output = new BufferedOutputStream(
                            Files.newOutputStream(destination), BUFFER_SIZE)) {
                        int read;
                        while ((read = zip.read(buffer)) >= 0) {
                            output.write(buffer, 0, read);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static Path findSingleExtractedDirectory(Path extractionRoot) throws IOException {
        try (Stream<Path> stream = Files.list(extractionRoot)) {
            List<Path> directories = stream.filter(Files::isDirectory).toList();
            if (directories.size() != 1) {
                throw new IOException(
                        "Impossibile identificare la directory estratta in " + extractionRoot
                                + ": trovate " + directories.size() + " directory");
            }
            return directories.get(0);
        }
    }

    private static List<Path> collectProductionJavaFiles(Path repositoryRoot, boolean includeTests)
            throws IOException {
        try (Stream<Path> stream = Files.walk(repositoryRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> isSelectedJavaSource(repositoryRoot, path, includeTests))
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return !name.equals("package-info.java") && !name.equals("module-info.java");
                    })
                    .map(path -> path.toAbsolutePath().normalize())
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static boolean isSelectedJavaSource(Path repositoryRoot, Path path, boolean includeTests) {
        Path relative = repositoryRoot.relativize(path);
        String normalized = slash(relative);
        if (normalized.contains("/target/")
                || normalized.startsWith("target/")
                || normalized.contains("/build/")
                || normalized.contains("/generated-sources/")
                || normalized.contains("/node_modules/")) {
            return false;
        }
        if (normalized.contains("/src/main/java/")) {
            return true;
        }
        return includeTests && normalized.contains("/src/test/java/");
    }

    private static void writeFileList(List<Path> javaFiles, Path fileList) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(fileList, StandardCharsets.UTF_8)) {
            for (Path javaFile : javaFiles) {
                writer.write(javaFile.toString());
                writer.newLine();
            }
        }
    }

    private static void executePmd(
            Path pmdRoot,
            Path fileList,
            Path ruleset,
            Path report,
            Path repositoryRoot,
            String threads) throws Exception {

        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path executable = pmdRoot.resolve("bin").resolve(windows ? "pmd.bat" : "pmd");
        if (!Files.isRegularFile(executable)) {
            throw new IOException("Eseguibile PMD non trovato: " + executable);
        }

        List<String> pmdArguments = List.of(
                "check",
                "--file-list", fileList.toString(),
                "--rulesets", ruleset.toString(),
                "--format", "xml",
                "--report-file", report.toString(),
                "--use-version", "java-25",
                "--encoding", "UTF-8",
                "--threads", threads,
                "--relativize-paths-with", repositoryRoot.toString(),
                "--no-cache",
                "--no-progress",
                "--no-fail-on-violation",
                "--no-fail-on-error");

        List<String> command = new ArrayList<>();
        if (windows) {
            command.add("cmd.exe");
            command.add("/d");
            command.add("/c");
        }
        command.add(executable.toString());
        command.addAll(pmdArguments);

        System.out.println("Avvio analisi PMD (Best Practices, Design, Error Prone, Multithreading, Performance)...");
        Process process = new ProcessBuilder(command)
                .directory(repositoryRoot.toFile())
                .redirectErrorStream(true)
                .start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("[PMD] " + line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("PMD terminato con codice " + exitCode
                    + ". Controllare le righe [PMD] immediatamente precedenti per individuare la causa.");
        }
        if (!Files.isRegularFile(report)) {
            throw new IOException("PMD non ha creato il report XML: " + report);
        }
    }

    private static Map<Path, ClassResult> initializeResults(Path repositoryRoot, List<Path> javaFiles)
            throws IOException {
        Map<Path, ClassResult> results = new LinkedHashMap<>();
        for (Path javaFile : javaFiles) {
            Path normalized = javaFile.toAbsolutePath().normalize();
            Path relative = repositoryRoot.relativize(normalized);
            String source = Files.readString(normalized, StandardCharsets.UTF_8);
            String simpleName = stripJavaExtension(normalized.getFileName().toString());
            Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
            String className = packageMatcher.find()
                    ? packageMatcher.group(1) + "." + simpleName
                    : simpleName;
            String module = determineModule(relative);
            results.put(normalized, new ClassResult(className, module, slash(relative)));
        }
        return results;
    }

    private static String determineModule(Path relativePath) {
        for (int index = 0; index + 2 < relativePath.getNameCount(); index++) {
            if (relativePath.getName(index).toString().equals("src")
                    && (relativePath.getName(index + 1).toString().equals("main")
                    || relativePath.getName(index + 1).toString().equals("test"))
                    && relativePath.getName(index + 2).toString().equals("java")) {
                return index == 0 ? "." : slash(relativePath.subpath(0, index));
            }
        }
        return ".";
    }

    private static ReportSummary parsePmdReport(
            Path repositoryRoot,
            Path reportPath,
            Map<Path, ClassResult> results) throws Exception {

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        Document document = factory.newDocumentBuilder().parse(reportPath.toFile());
        List<String> configurationErrors = new ArrayList<>();

        NodeList fileNodes = document.getElementsByTagNameNS("*", "file");
        for (int fileIndex = 0; fileIndex < fileNodes.getLength(); fileIndex++) {
            Element fileElement = (Element) fileNodes.item(fileIndex);
            ClassResult result = findResult(repositoryRoot, fileElement.getAttribute("name"), results);
            if (result == null) {
                continue;
            }
            NodeList children = fileElement.getChildNodes();
            for (int childIndex = 0; childIndex < children.getLength(); childIndex++) {
                Node child = children.item(childIndex);
                if (child instanceof Element violation
                        && localName(violation).equals("violation")) {
                    String rule = defaultIfBlank(violation.getAttribute("rule"), "UnknownRule");
                    String ruleSet = defaultIfBlank(violation.getAttribute("ruleset"), "UnknownRuleSet");
                    result.addViolation(ruleSet, rule);
                }
            }
        }

        NodeList errorNodes = document.getElementsByTagNameNS("*", "error");
        for (int index = 0; index < errorNodes.getLength(); index++) {
            Element error = (Element) errorNodes.item(index);
            String fileName = error.getAttribute("filename");
            ClassResult result = findResult(repositoryRoot, fileName, results);
            if (result != null) {
                String message = defaultIfBlank(error.getAttribute("msg"), error.getTextContent());
                result.addError(message.strip());
            }
        }

        NodeList configErrorNodes = document.getElementsByTagNameNS("*", "configerror");
        for (int index = 0; index < configErrorNodes.getLength(); index++) {
            Element error = (Element) configErrorNodes.item(index);
            String rule = defaultIfBlank(error.getAttribute("rule"), "unknown rule");
            String message = defaultIfBlank(error.getAttribute("msg"), error.getTextContent());
            configurationErrors.add(rule + ": " + message.strip());
        }

        List<String> fatalConfigurationErrors = new ArrayList<>();
        for (String configurationError : configurationErrors) {
            if (configurationError.startsWith("LoosePackageCoupling:")
                    && configurationError.contains("No packages or classes specified")) {
                System.out.println(
                        "AVVISO: PMD ha escluso LoosePackageCoupling perché la regola "
                                + "richiede un elenco esplicito di package o classi. "
                                + "L'analisi continua con tutte le altre regole.");
            } else {
                fatalConfigurationErrors.add(configurationError);
            }
        }

        if (!fatalConfigurationErrors.isEmpty()) {
            throw new IllegalStateException(
                    "PMD ha segnalato errori di configurazione non ignorabili: "
                            + String.join(" | ", fatalConfigurationErrors));
        }

        return new ReportSummary(configurationErrors);
    }

    private static ClassResult findResult(
            Path repositoryRoot,
            String reportedName,
            Map<Path, ClassResult> results) {
        if (reportedName == null || reportedName.isBlank()) {
            return null;
        }

        try {
            Path reportedPath = Path.of(reportedName);
            if (!reportedPath.isAbsolute()) {
                reportedPath = repositoryRoot.resolve(reportedPath);
            }
            ClassResult exact = results.get(reportedPath.toAbsolutePath().normalize());
            if (exact != null) {
                return exact;
            }
        } catch (RuntimeException ignored) {
            // Fallback to normalized suffix matching below.
        }

        String normalizedName = reportedName.replace('\\', '/');
        while (normalizedName.startsWith("./")) {
            normalizedName = normalizedName.substring(2);
        }
        for (Map.Entry<Path, ClassResult> entry : results.entrySet()) {
            if (slash(entry.getKey()).endsWith(normalizedName)
                    || entry.getValue().relativePath().equals(normalizedName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static void writeCsv(Path outputCsv, Iterable<ClassResult> values) throws IOException {
        List<ClassResult> sorted = new ArrayList<>();
        values.forEach(sorted::add);
        sorted.sort(Comparator
                .comparingLong(ClassResult::smellCount).reversed()
                .thenComparing(ClassResult::className)
                .thenComparing(ClassResult::relativePath));

        try (OutputStream raw = Files.newOutputStream(outputCsv);
             BufferedWriter writer = new BufferedWriter(
                     new java.io.OutputStreamWriter(raw, StandardCharsets.UTF_8))) {
            // UTF-8 BOM helps Microsoft Excel recognize the encoding.
            writer.write('\uFEFF');
            writeCsvRow(writer, List.of(
                    "Rank",
                    "ClassName",
                    "Module",
                    "RelativePath",
                    "CodeSmellCount",
                    "DistinctRuleCount",
                    "PMDRuleSets",
                    "PMDRules",
                    "PMDRuleCounts",
                    "PMDAnalysisStatus",
                    "PMDAnalysisError"));

            int rank = 1;
            for (ClassResult result : sorted) {
                List<String> rules = new ArrayList<>(result.ruleCounts().keySet());
                List<String> ruleCountPairs = result.ruleCounts().entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue())
                        .toList();
                writeCsvRow(writer, List.of(
                        Integer.toString(rank++),
                        result.className(),
                        result.module(),
                        result.relativePath(),
                        Long.toString(result.smellCount()),
                        Integer.toString(result.ruleCounts().size()),
                        String.join("; ", result.ruleSets().keySet()),
                        String.join("; ", rules),
                        String.join("; ", ruleCountPairs),
                        result.hasErrors() ? "PARTIAL_ERROR" : "SUCCESS",
                        String.join(" | ", result.errors())));
            }
        }
    }

    private static void writeCsvRow(BufferedWriter writer, List<String> values) throws IOException {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                writer.write(',');
            }
            writer.write(csvEscape(values.get(index)));
        }
        writer.newLine();
    }

    private static String csvEscape(String value) {
        String safe = Objects.toString(value, "");
        boolean quote = safe.indexOf(',') >= 0
                || safe.indexOf('"') >= 0
                || safe.indexOf('\n') >= 0
                || safe.indexOf('\r') >= 0;
        if (!quote) {
            return safe;
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static void copyClasspathResource(String resourceName, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        try (InputStream input = SyncopePmdCsvGenerator.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Risorsa non trovata nel progetto: " + resourceName);
            }
            Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void ensureUnixPmdIsExecutable(Path pmdRoot) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            pmdRoot.resolve("bin").resolve("pmd").toFile().setExecutable(true, false);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception)
                    throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String localName(Element element) {
        return element.getLocalName() == null ? element.getTagName() : element.getLocalName();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? Objects.toString(fallback, "") : value;
    }

    private static String stripJavaExtension(String name) {
        return name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
    }

    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void printHelp() {
        System.out.println("SyncopePmdCsvGenerator");
        System.out.println();
        System.out.println("Esegue automaticamente:");
        System.out.println("  1. download del fork https://github.com/jadepics/syncope");
        System.out.println("  2. download di PMD " + PMD_VERSION);
        System.out.println("  3. analisi dei file sotto src/main/java");
        System.out.println("  4. creazione del CSV ordinato per CodeSmellCount decrescente");
        System.out.println();
        System.out.println("Opzioni:");
        System.out.println("  --project-root <path>       Root del progetto esterno (default: working directory)");
        System.out.println("  --runtime-dir <path>        Cartella download/cache (default: runtime)");
        System.out.println("  --output <path>             CSV di output (default: output/syncope_classes_by_codesmell.csv)");
        System.out.println("  --repository-zip-url <url>  URL ZIP da analizzare");
        System.out.println("  --branch <name>             Etichetta del branch (default: master)");
        System.out.println("  --reuse-repository          Non riscarica Syncope se già presente");
        System.out.println("  --refresh-pmd               Riscarica anche PMD");
        System.out.println("  --include-tests             Include anche src/test/java");
        System.out.println("  --threads <value>           Thread PMD, es. 1C o 4 (default: 1C)");
        System.out.println("  --help                      Mostra questo testo");
    }

    private record Options(
            Path projectRoot,
            Path runtimeDirectory,
            Path outputCsv,
            String repositoryZipUrl,
            String branch,
            boolean refreshRepository,
            boolean refreshPmd,
            boolean includeTests,
            String threads,
            boolean help) {

        private static Options parse(String[] args) {
            Path projectRoot = Path.of(System.getProperty("user.dir"));
            Path runtimeDirectory = Path.of("runtime");
            Path outputCsv = Path.of("output", "syncope_classes_by_codesmell.csv");
            String repositoryZipUrl = DEFAULT_REPOSITORY_ZIP;
            String branch = DEFAULT_BRANCH;
            boolean refreshRepository = true;
            boolean refreshPmd = false;
            boolean includeTests = false;
            String threads = "1C";
            boolean help = false;

            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                switch (argument) {
                    case "--project-root" -> projectRoot = Path.of(requireValue(args, ++index, argument));
                    case "--runtime-dir" -> runtimeDirectory = Path.of(requireValue(args, ++index, argument));
                    case "--output" -> outputCsv = Path.of(requireValue(args, ++index, argument));
                    case "--repository-zip-url" -> repositoryZipUrl = requireValue(args, ++index, argument);
                    case "--branch" -> branch = requireValue(args, ++index, argument);
                    case "--threads" -> threads = requireValue(args, ++index, argument);
                    case "--reuse-repository" -> refreshRepository = false;
                    case "--refresh-pmd" -> refreshPmd = true;
                    case "--include-tests" -> includeTests = true;
                    case "--help", "-h" -> help = true;
                    default -> throw new IllegalArgumentException("Argomento sconosciuto: " + argument);
                }
            }

            return new Options(
                    projectRoot,
                    runtimeDirectory,
                    outputCsv,
                    repositoryZipUrl,
                    branch,
                    refreshRepository,
                    refreshPmd,
                    includeTests,
                    threads,
                    help);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("--")) {
                throw new IllegalArgumentException("Valore mancante per " + option);
            }
            return args[index];
        }
    }

    private static final class ClassResult {
        private final String className;
        private final String module;
        private final String relativePath;
        private final Map<String, Long> ruleCounts = new TreeMap<>();
        private final Map<String, Boolean> ruleSets = new TreeMap<>();
        private final List<String> errors = new ArrayList<>();

        private ClassResult(String className, String module, String relativePath) {
            this.className = className;
            this.module = module;
            this.relativePath = relativePath;
        }

        private void addViolation(String ruleSet, String rule) {
            ruleCounts.merge(rule, 1L, Long::sum);
            ruleSets.put(ruleSet, Boolean.TRUE);
        }

        private void addError(String error) {
            if (error != null && !error.isBlank()) {
                errors.add(error);
            }
        }

        private String className() {
            return className;
        }

        private String module() {
            return module;
        }

        private String relativePath() {
            return relativePath;
        }

        private Map<String, Long> ruleCounts() {
            return ruleCounts;
        }

        private Map<String, Boolean> ruleSets() {
            return ruleSets;
        }

        private List<String> errors() {
            return errors;
        }

        private long smellCount() {
            return ruleCounts.values().stream().mapToLong(Long::longValue).sum();
        }

        private boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    private record ReportSummary(List<String> configurationErrors) {
    }
}
