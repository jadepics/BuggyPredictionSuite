package it.university.buggyprediction.milestone1;

import static it.university.buggyprediction.milestone1.Milestone1Constants.GITHUB_API_VERSION;
import static it.university.buggyprediction.milestone1.Milestone1Constants.MAX_HTTP_ATTEMPTS;
import static it.university.buggyprediction.milestone1.Milestone1Constants.SYNCOPE_GITHUB_ARCHIVE_BASE_URL;
import static it.university.buggyprediction.milestone1.Milestone1Constants.SYNCOPE_REPOSITORY_URL;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Facade for all GitHub and Git operations used by Milestone 1.
 */
final class GitHubService {

    private final Milestone1Config config;
    private Path mirror;
    private GitClient gitClient;
    private SourceArchiveManager archiveManager;

    GitHubService(final Milestone1Config config) {
        this.config = config;
    }

    void prepare() throws Exception {
        mirror = SyncopeGitHubMirror.prepare(
                config.milestoneRoot(),
                config.githubRefresh());
        gitClient = new GitClient(mirror, new IssueKeyExtractor());
        gitClient.assertRepositoryAvailable();
        archiveManager = new SourceArchiveManager();
    }

    Path mirrorPath() {
        ensurePrepared();
        return mirror;
    }

    Map<String, String> collectTagsByCanonicalVersion() throws Exception {
        ensurePrepared();
        return gitClient.collectTagsByCanonicalVersion();
    }

    Map<String, List<GitCommit>> collectIssueCommits() throws Exception {
        ensurePrepared();
        return gitClient.collectIssueCommits();
    }

    Map<String, FileProcessMetrics> collectFileProcessMetrics(
            final Release release,
            final Set<String> validatedFixCommitHashes) throws Exception {
        ensurePrepared();
        return gitClient.collectFileProcessMetrics(
                release,
                validatedFixCommitHashes);
    }

    String commitForTag(final String tag) throws Exception {
        ensurePrepared();
        return gitClient.commitForTag(tag);
    }

    LocalDate commitDateForTag(final String tag) throws Exception {
        ensurePrepared();
        return gitClient.commitDateForTag(tag);
    }

    String currentHead() throws Exception {
        ensurePrepared();
        return gitClient.currentHead();
    }

    SourceSnapshot createSnapshot(final Release release) throws Exception {
        ensurePrepared();
        return archiveManager.createSnapshot(release);
    }

    private void ensurePrepared() {
        if (gitClient == null || archiveManager == null) {
            throw new IllegalStateException(
                    "GitHubService non inizializzato: chiamare prepare().");
        }
    }
}


final class GitCommandRunner {

    String execute(
            final Path workingDirectory,
            final List<String> arguments,
            final Duration timeout) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(arguments);

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        Process process = builder.start();

        String output;
        try (InputStream stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        boolean completed = process.waitFor(
                timeout.toMillis(),
                TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new IllegalStateException(
                    "Comando Git scaduto: " + String.join(" ", command));
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(
                    "Comando Git fallito (exit " + process.exitValue() + "): "
                            + String.join(" ", command)
                            + System.lineSeparator()
                            + output);
        }
        return output;
    }
}


final class IssueKeyExtractor {

    private static final Pattern PATTERN =
            Pattern.compile("(?i)(?<![A-Z0-9])SYNCOPE-\\d+(?![A-Z0-9]|\\.\\d)");

    Set<String> extract(final String subject) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = PATTERN.matcher(subject == null ? "" : subject);
        while (matcher.find()) {
            result.add(matcher.group().toUpperCase(Locale.ROOT));
        }
        return result;
    }
}


final class SyncopeGitHubMirror {

    private static final GitCommandRunner GIT = new GitCommandRunner();
    private static final Logger LOGGER =
            Logger.getLogger(SyncopeGitHubMirror.class.getName());

    private SyncopeGitHubMirror() {
    }

    static Path prepare(
            final Path milestoneRoot,
            final boolean refresh) throws IOException, InterruptedException {
        Path workspace = milestoneRoot.resolve("workspace")
                .resolve("github")
                .toAbsolutePath()
                .normalize();
        Path mirror = workspace.resolve("apache-syncope.git");
        Files.createDirectories(workspace);

        if (!isBareRepository(mirror)) {
            if (Files.exists(mirror)) {
                LOGGER.warning(() ->
                        "Cache GitHub incompleta rilevata; eliminazione di " + mirror);
                FileSystemUtils.deleteRecursively(mirror);
            }
            LOGGER.info(() ->
                    "Prima esecuzione: download della cronologia completa da "
                            + SYNCOPE_REPOSITORY_URL);
            cloneMirror(workspace, mirror);
        }

        if (refresh) {
            LOGGER.info("Aggiornamento del mirror GitHub Apache Syncope...");
            GIT.execute(
                    workspace,
                    List.of(
                            "--git-dir=" + mirror,
                            "remote",
                            "update",
                            "--prune"),
                    Duration.ofMinutes(30));
        } else {
            LOGGER.info(
                    "Refresh del mirror GitHub disabilitato tramite "
                            + "-Dmilestone.githubRefresh=false");
        }

        if (!isBareRepository(mirror)) {
            throw new IllegalStateException(
                    "Il mirror GitHub Apache Syncope non è utilizzabile: " + mirror);
        }

        String origin = GIT.execute(
                workspace,
                List.of(
                        "--git-dir=" + mirror,
                        "remote",
                        "get-url",
                        "origin"),
                Duration.ofMinutes(2)).trim();
        if (!normalizeRemote(origin).equals(
                normalizeRemote(SYNCOPE_REPOSITORY_URL))) {
            throw new IllegalStateException(
                    "La cache Git punta a un'origine inattesa: " + origin);
        }
        return mirror;
    }

    private static void cloneMirror(
            final Path workspace,
            final Path mirror) throws IOException, InterruptedException {
        try {
            GIT.execute(
                    workspace,
                    List.of(
                            "clone",
                            "--mirror",
                            "--filter=blob:none",
                            SYNCOPE_REPOSITORY_URL,
                            mirror.toString()),
                    Duration.ofMinutes(45));
        } catch (IllegalStateException partialCloneFailure) {
            LOGGER.log(
                    Level.WARNING,
                    "Il clone filtrato non è supportato; nuovo tentativo "
                            + "con mirror completo.",
                    partialCloneFailure);
            FileSystemUtils.deleteRecursively(mirror);
            GIT.execute(
                    workspace,
                    List.of(
                            "clone",
                            "--mirror",
                            SYNCOPE_REPOSITORY_URL,
                            mirror.toString()),
                    Duration.ofMinutes(60));
        }
    }

    private static boolean isBareRepository(final Path mirror)
            throws IOException, InterruptedException {
        if (!Files.isDirectory(mirror)) {
            return false;
        }
        try {
            String result = GIT.execute(
                    mirror.getParent(),
                    List.of(
                            "--git-dir=" + mirror,
                            "rev-parse",
                            "--is-bare-repository"),
                    Duration.ofMinutes(2)).trim();
            return "true".equalsIgnoreCase(result);
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    private static String normalizeRemote(final String remote) {
        if (remote == null) {
            return "";
        }
        return remote.trim()
                .toLowerCase(Locale.ROOT)
                .replaceFirst("\\.git$", "")
                .replace("git@github.com:", "https://github.com/");
    }
}


final class GitClient {

    private static final Logger LOGGER = Logger.getLogger(GitClient.class.getName());
    private static final String HISTORY_COMMIT_PREFIX = "__M1_COMMIT__";

    private final Path gitDirectory;
    private final IssueKeyExtractor issueKeyExtractor;
    private final GitCommandRunner commandRunner = new GitCommandRunner();

    GitClient(
            final Path gitDirectory,
            final IssueKeyExtractor issueKeyExtractor) {
        this.gitDirectory = gitDirectory;
        this.issueKeyExtractor = issueKeyExtractor;
    }

    void assertRepositoryAvailable() throws IOException, InterruptedException {
        String bare = run(List.of(
                "rev-parse",
                "--is-bare-repository")).trim();
        if (!"true".equalsIgnoreCase(bare)) {
            throw new IllegalStateException(
                    "La cache GitHub non è un repository Git bare valido.");
        }
    }

    String currentHead() throws IOException, InterruptedException {
        return run(List.of("rev-parse", "HEAD")).trim();
    }

    Map<String, String> collectTagsByCanonicalVersion()
            throws IOException, InterruptedException {
        String output = run(List.of("tag", "--list"));
        Map<String, List<String>> candidates = new HashMap<>();

        for (String line : output.split("\\R")) {
            String tag = line.trim();
            if (tag.isBlank()) {
                continue;
            }
            String canonical = VersionUtils.canonicalVersion(tag);
            if (canonical.isBlank()) {
                continue;
            }
            candidates.computeIfAbsent(
                    canonical,
                    ignored -> new ArrayList<>()).add(tag);
        }

        Map<String, String> selected = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : candidates.entrySet()) {
            List<String> tags = entry.getValue();
            tags.sort(Comparator
                    .comparingInt((String tag) ->
                            tag.toLowerCase(Locale.ROOT).startsWith("syncope-")
                                    ? 0
                                    : 1)
                    .thenComparingInt(String::length)
                    .thenComparing(Function.identity()));
            selected.put(entry.getKey(), tags.getFirst());
        }
        return selected;
    }

    String commitForTag(final String tag)
            throws IOException, InterruptedException {
        return run(List.of("rev-list", "-n", "1", tag)).trim();
    }

    LocalDate commitDateForTag(final String tag)
            throws IOException, InterruptedException {
        String value = run(List.of(
                "show",
                "-s",
                "--format=%cI",
                tag + "^{commit}")).trim();
        OffsetDateTime date = parseGitDate(value);
        return date == null ? null : date.toLocalDate();
    }

    Map<String, List<GitCommit>> collectIssueCommits()
            throws IOException, InterruptedException {
        String output = run(List.of(
                "log",
                "--all",
                "--format=%H%x1f%aI%x1f%cI%x1f%s"));

        Map<String, List<GitCommit>> result = new HashMap<>();
        Map<String, GitCommit> commitsByHash = new LinkedHashMap<>();
        Map<String, Set<String>> issueKeysByHash = new LinkedHashMap<>();

        for (String line : output.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\u001f", 4);
            if (parts.length < 4) {
                continue;
            }

            Set<String> keys = issueKeyExtractor.extract(parts[3]);
            if (keys.isEmpty()) {
                continue;
            }

            GitCommit commit = new GitCommit(
                    parts[0],
                    parseGitDate(parts[1]),
                    parseGitDate(parts[2]),
                    parts[3]);
            commitsByHash.putIfAbsent(commit.hash, commit);
            issueKeysByHash.computeIfAbsent(
                    commit.hash,
                    ignored -> new LinkedHashSet<>()).addAll(keys);
        }

        int counter = 0;
        for (GitCommit commit : commitsByHash.values()) {
            counter++;
            commit.fileChanges.addAll(changedFiles(commit.hash));
            for (String issueKey
                    : issueKeysByHash.getOrDefault(commit.hash, Set.of())) {
                result.computeIfAbsent(
                        issueKey,
                        ignored -> new ArrayList<>()).add(commit.copy());
            }
            if (counter % 100 == 0) {
                int current = counter;
                LOGGER.info(() ->
                        "Commit con ticket analizzati: " + current
                                + " / " + commitsByHash.size());
            }
        }

        for (List<GitCommit> commits : result.values()) {
            commits.sort(Comparator
                    .comparing(
                            (GitCommit commit) -> commit.committerDate,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(commit -> commit.hash));
        }
        return result;
    }

    Map<String, FileProcessMetrics> collectFileProcessMetrics(
            final Release release,
            final Set<String> validatedFixCommitHashes)
            throws IOException, InterruptedException {
        return new GitProcessMetricsCollector(gitDirectory, commandRunner)
                .collect(release, validatedFixCommitHashes);
    }

    private List<FileChange> changedFiles(final String commitHash)
            throws IOException, InterruptedException {
        String output = run(List.of(
                "show",
                "--format=",
                "--name-status",
                "--find-renames",
                "--find-copies",
                commitHash));

        List<FileChange> changes = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\t");
            if (parts.length < 2) {
                continue;
            }
            String status = parts[0].trim();
            if ((status.startsWith("R") || status.startsWith("C"))
                    && parts.length >= 3) {
                changes.add(new FileChange(
                        status,
                        normalizeGitPath(parts[1]),
                        normalizeGitPath(parts[2])));
            } else {
                changes.add(new FileChange(
                        status,
                        null,
                        normalizeGitPath(parts[1])));
            }
        }
        return changes;
    }

    private String run(final List<String> arguments)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("--git-dir=" + gitDirectory);
        command.addAll(arguments);
        return commandRunner.execute(
                gitDirectory.getParent(),
                command,
                Duration.ofMinutes(30));
    }

    private static OffsetDateTime parseGitDate(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(
                    value,
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static String normalizeGitPath(final String path) {
        return path == null ? null : path.trim().replace('\\', '/');
    }


}


final class GitProcessMetricsCollector {

    private static final Logger LOGGER =
            Logger.getLogger(GitProcessMetricsCollector.class.getName());
    private static final String HISTORY_COMMIT_PREFIX = "__M1_COMMIT__";

    private final Path gitDirectory;
    private final GitCommandRunner commandRunner;

    GitProcessMetricsCollector(
            final Path gitDirectory,
            final GitCommandRunner commandRunner) {
        this.gitDirectory = gitDirectory;
        this.commandRunner = commandRunner;
    }

    Map<String, FileProcessMetrics> collect(
            final Release release,
            final Set<String> validatedFixCommitHashes)
            throws IOException, InterruptedException {
        if (release.gitTag == null || release.gitTag.isBlank()) {
            return Map.of();
        }

        LOGGER.info(() ->
                "Calcolo metriche di processo Git per " + release.version);

        String output = run(List.of(
                "log",
                "--reverse",
                "--date-order",
                "--no-renames",
                "--format=" + HISTORY_COMMIT_PREFIX
                        + "%x1f%H%x1f%aE%x1f%cI",
                "--numstat",
                release.gitTag));

        Map<String, FileHistoryAccumulator> accumulators = new LinkedHashMap<>();
        HistoryCommit current = null;
        for (String line : output.split("\\R", -1)) {
            if (line.startsWith(HISTORY_COMMIT_PREFIX + "\u001f")) {
                if (current != null) {
                    accumulateCommit(
                            current,
                            release,
                            validatedFixCommitHashes,
                            accumulators);
                }
                current = parseHistoryCommit(line);
            } else if (current != null && !line.isBlank()) {
                HistoryFileChange change = parseNumstat(line);
                if (change != null) {
                    current.changes.add(change);
                }
            }
        }
        if (current != null) {
            accumulateCommit(
                    current,
                    release,
                    validatedFixCommitHashes,
                    accumulators);
        }

        Map<String, FileProcessMetrics> result = new LinkedHashMap<>();
        for (Map.Entry<String, FileHistoryAccumulator> entry
                : accumulators.entrySet()) {
            result.put(entry.getKey(), entry.getValue().finish(release.releaseDate));
        }
        return result;
    }

    private String run(final List<String> arguments)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("--git-dir=" + gitDirectory);
        command.addAll(arguments);
        return commandRunner.execute(
                gitDirectory.getParent(),
                command,
                Duration.ofMinutes(30));
    }

    private static HistoryCommit parseHistoryCommit(final String line) {
        String[] parts = line.split("\\u001f", 4);
        if (parts.length < 4) {
            return new HistoryCommit("", "", null);
        }
        return new HistoryCommit(
                parts[1],
                parts[2],
                parseGitDate(parts[3]));
    }

    private static HistoryFileChange parseNumstat(final String line) {
        String[] parts = line.split("\\t", 3);
        if (parts.length < 3) {
            return null;
        }
        return new HistoryFileChange(
                normalizeGitPath(parts[2]),
                parseNumstatNumber(parts[0]),
                parseNumstatNumber(parts[1]));
    }

    private static int parseNumstatNumber(final String raw) {
        if (raw == null || raw.isBlank() || "-".equals(raw)) {
            return 0;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static void accumulateCommit(
            final HistoryCommit commit,
            final Release release,
            final Set<String> validatedFixCommitHashes,
            final Map<String, FileHistoryAccumulator> accumulators) {
        if (commit.hash.isBlank() || commit.committerDate == null) {
            return;
        }

        LocalDate date = commit.committerDate.toLocalDate();
        if (release.releaseDate != null && date.isAfter(release.releaseDate)) {
            return;
        }

        int changeSetSize = commit.changes.size();
        boolean defectFix = validatedFixCommitHashes.contains(commit.hash);
        for (HistoryFileChange change : commit.changes) {
            if (!ProductionJavaPathFilter.isProductionJavaPath(change.path)) {
                continue;
            }
            accumulators.computeIfAbsent(
                    change.path,
                    ignored -> new FileHistoryAccumulator())
                    .add(
                            commit.hash,
                            commit.authorEmail,
                            date,
                            change.added,
                            change.deleted,
                            changeSetSize,
                            defectFix);
        }
    }

    private static OffsetDateTime parseGitDate(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(
                    value,
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static String normalizeGitPath(final String path) {
        return path == null ? null : path.trim().replace('\\', '/');
    }

    private static final class HistoryCommit {
        final String hash;
        final String authorEmail;
        final OffsetDateTime committerDate;
        final List<HistoryFileChange> changes = new ArrayList<>();

        HistoryCommit(
                final String hash,
                final String authorEmail,
                final OffsetDateTime committerDate) {
            this.hash = hash;
            this.authorEmail = authorEmail;
            this.committerDate = committerDate;
        }
    }

    private record HistoryFileChange(String path, int added, int deleted) {
    }

    private static final class FileHistoryAccumulator {
        final Set<String> authors = new LinkedHashSet<>();
        final Set<String> commits = new LinkedHashSet<>();
        final List<RevisionPoint> revisionPoints = new ArrayList<>();
        int defectFixes;
        int locAdded;
        int maxLocAdded;
        int locDeleted;
        int maxChurn;
        int totalChangeSet;
        int maxChangeSet;

        void add(
                final String hash,
                final String authorEmail,
                final LocalDate date,
                final int added,
                final int deleted,
                final int changeSetSize,
                final boolean defectFix) {
            if (!commits.add(hash)) {
                return;
            }
            if (authorEmail != null && !authorEmail.isBlank()) {
                authors.add(authorEmail.toLowerCase(Locale.ROOT));
            }
            revisionPoints.add(new RevisionPoint(date, added + deleted));
            if (defectFix) {
                defectFixes++;
            }

            locAdded += added;
            locDeleted += deleted;
            maxLocAdded = Math.max(maxLocAdded, added);
            int revisionChurn = added + deleted;
            maxChurn = Math.max(maxChurn, revisionChurn);
            totalChangeSet += changeSetSize;
            maxChangeSet = Math.max(maxChangeSet, changeSetSize);
        }

        FileProcessMetrics finish(final LocalDate releaseDate) {
            FileProcessMetrics metrics = new FileProcessMetrics();
            metrics.revisions = commits.size();
            metrics.defectFixes = defectFixes;
            metrics.authors = authors.size();
            metrics.locAdded = locAdded;
            metrics.maxLocAdded = maxLocAdded;
            metrics.averageLocAdded = average(locAdded, metrics.revisions);
            metrics.locTouched = locAdded + locDeleted;
            metrics.maxChurn = maxChurn;
            metrics.averageChurn = average(metrics.locTouched, metrics.revisions);
            metrics.changeSetSize = totalChangeSet;
            metrics.maxChangeSet = maxChangeSet;
            metrics.averageChangeSet = average(totalChangeSet, metrics.revisions);

            if (releaseDate == null || revisionPoints.isEmpty()) {
                return metrics;
            }

            revisionPoints.sort(Comparator.comparing(RevisionPoint::date));
            List<LocalDate> changeDates = revisionPoints.stream()
                    .map(RevisionPoint::date)
                    .toList();
            metrics.age = nonNegativeDays(changeDates.getFirst(), releaseDate);
            metrics.averageChangeInterval = averageIntervals(changeDates);

            double weightedNumerator = 0.0d;
            int weightedDenominator = 0;
            for (RevisionPoint point : revisionPoints) {
                if (point.touched() <= 0) {
                    continue;
                }
                weightedNumerator += nonNegativeDays(point.date(), releaseDate)
                        * point.touched();
                weightedDenominator += point.touched();
            }
            metrics.weightedAge = weightedDenominator == 0
                    ? metrics.age
                    : weightedNumerator / weightedDenominator;
            return metrics;
        }

        private record RevisionPoint(LocalDate date, int touched) {
        }

        private static double average(final int total, final int count) {
            return count == 0 ? 0.0d : (double) total / count;
        }

        private static long nonNegativeDays(
                final LocalDate from,
                final LocalDate to) {
            return Math.max(0L, ChronoUnit.DAYS.between(from, to));
        }

        private static double averageIntervals(final List<LocalDate> dates) {
            if (dates.size() < 2) {
                return 0.0d;
            }
            long total = 0L;
            for (int index = 1; index < dates.size(); index++) {
                total += nonNegativeDays(
                        dates.get(index - 1),
                        dates.get(index));
            }
            return (double) total / (dates.size() - 1);
        }
    }
}


final class SourceArchiveManager {

    private static final Logger LOGGER =
            Logger.getLogger(SourceArchiveManager.class.getName());

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    SourceSnapshot createSnapshot(final Release release)
            throws IOException, InterruptedException {
        if (release.gitTag == null) {
            throw new IllegalArgumentException(
                    "La release non possiede un tag Git: " + release.version);
        }

        Path temporaryRoot = Files.createTempDirectory(
                "syncope-m1-"
                        + release.canonicalVersion.replaceAll(
                                "[^a-zA-Z0-9._-]",
                                "_"));
        Path zipFile = temporaryRoot.resolve("source.zip");
        Path sourceDirectory = temporaryRoot.resolve("source");
        Files.createDirectories(sourceDirectory);

        try {
            downloadGitHubArchive(release.gitTag, zipFile);
            unzip(zipFile, sourceDirectory);
            Files.deleteIfExists(zipFile);
            Path repositoryRoot = locateRepositoryRoot(sourceDirectory);
            return new SourceSnapshot(temporaryRoot, repositoryRoot);
        } catch (Exception exception) {
            FileSystemUtils.deleteRecursively(temporaryRoot);
            throw exception;
        }
    }

    private void downloadGitHubArchive(
            final String tag,
            final Path zipFile) throws IOException, InterruptedException {
        URI uri = URI.create(
                SYNCOPE_GITHUB_ARCHIVE_BASE_URL + encodePathSegment(tag));
        IOException lastIOException = null;

        for (int attempt = 1; attempt <= MAX_HTTP_ATTEMPTS; attempt++) {
            try {
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(Duration.ofMinutes(10))
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
                        .header(
                                "User-Agent",
                                "Syncope-Milestone-1-Dataset-Builder")
                        .GET();

                String token = System.getenv("GITHUB_TOKEN");
                if (token != null && !token.isBlank()) {
                    requestBuilder.header(
                            "Authorization",
                            "Bearer " + token.trim());
                }

                LOGGER.info(() ->
                        "Download source archive GitHub per tag " + tag);
                HttpResponse<Path> response = client.send(
                        requestBuilder.build(),
                        HttpResponse.BodyHandlers.ofFile(zipFile));

                int statusCode = response.statusCode();
                if (statusCode >= 200 && statusCode < 300) {
                    return;
                }

                Files.deleteIfExists(zipFile);
                if ((statusCode == 429 || statusCode >= 500)
                        && attempt < MAX_HTTP_ATTEMPTS) {
                    Thread.sleep(Duration.ofSeconds(attempt * 2L).toMillis());
                    continue;
                }

                throw new IllegalStateException(
                        "GitHub ha restituito HTTP " + statusCode
                                + " durante il download del tag " + tag);
            } catch (IOException exception) {
                lastIOException = exception;
                Files.deleteIfExists(zipFile);
                if (attempt < MAX_HTTP_ATTEMPTS) {
                    Thread.sleep(Duration.ofSeconds(attempt * 2L).toMillis());
                }
            }
        }

        throw lastIOException == null
                ? new IOException(
                        "Impossibile scaricare da GitHub il tag " + tag)
                : lastIOException;
    }

    private static Path locateRepositoryRoot(final Path sourceDirectory)
            throws IOException {
        try (var stream = Files.list(sourceDirectory)) {
            List<Path> entries = stream.toList();
            if (entries.size() == 1 && Files.isDirectory(entries.getFirst())) {
                return entries.getFirst();
            }
            return sourceDirectory;
        }
    }

    private static String encodePathSegment(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%2F", "/");
    }

    private static void unzip(
            final Path zipFile,
            final Path destination) throws IOException {
        try (InputStream input = Files.newInputStream(zipFile);
             ZipInputStream zipInput =
                     new ZipInputStream(input, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                Path target = destination.resolve(entry.getName()).normalize();
                if (!target.startsWith(destination.normalize())) {
                    throw new IOException(
                            "Archivio ZIP non sicuro: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream output = Files.newOutputStream(target)) {
                        zipInput.transferTo(output);
                    }
                }
                zipInput.closeEntry();
            }
        }
    }
}


record SourceSnapshot(
        Path temporaryRoot,
        Path repositoryRoot) implements AutoCloseable {

    @Override
    public void close() throws IOException {
        FileSystemUtils.deleteRecursively(temporaryRoot);
    }
}
