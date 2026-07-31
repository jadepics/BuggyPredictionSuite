package it.university.buggyprediction.milestone1;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class Milestone1Constants {
    static final String PIPELINE_REVISION = "m1-pmd-smells-proportion-total-2026-07-31";
    static final String PROJECT_KEY = "SYNCOPE";
    static final String PROJECT_NAME = "Apache Syncope";
    static final String SYNCOPE_REPOSITORY_URL = "https://github.com/apache/syncope.git";
    static final String SYNCOPE_GITHUB_ARCHIVE_BASE_URL =
            "https://api.github.com/repos/apache/syncope/zipball/";
    static final String GITHUB_API_VERSION = "2026-03-10";
    static final String JIRA_BASE_URL = "https://issues.apache.org/jira";
    static final String JIRA_VERSIONS_ENDPOINT =
            JIRA_BASE_URL + "/rest/api/2/project/" + PROJECT_KEY + "/versions";
    static final String JIRA_SEARCH_ENDPOINT = JIRA_BASE_URL + "/rest/api/2/search";
    static final String JQL =
            "project = " + PROJECT_KEY + " AND issuetype = Bug ORDER BY created ASC";
    static final String ISSUE_FIELDS = String.join(",",
            "summary", "status", "resolution", "priority", "created",
            "resolutiondate", "versions", "fixVersions");
    static final int PAGE_SIZE = 100;
    static final int MAX_HTTP_ATTEMPTS = 3;
    static final double DEFAULT_RELEASE_FRACTION = 0.33d;
    static final ObjectMapper JSON = new ObjectMapper();
    static final String OFFICIAL_RELEASE_HISTORY_URL =
            "https://cwiki.apache.org/confluence/display/SYNCOPE/Espressivo";
    static final Map<String, ReleaseDateOverride> RELEASE_DATE_OVERRIDES = Map.of(
            "1.0.3-incubating",
            new ReleaseDateOverride(
                    LocalDate.of(2012, 10, 30),
                    OFFICIAL_RELEASE_HISTORY_URL,
                    "La cronologia ufficiale Apache indica 30/10/2012; "
                            + "JIRA riporta 30/09/2012."));

    private Milestone1Constants() {
    }
}


final class VersionUtils {
    private VersionUtils() {
    }

    static String canonicalVersion(final String rawValue) {
        if (rawValue == null) {
            return "";
        }
        String value = rawValue.trim().toLowerCase(Locale.ROOT);
        value = value.replaceFirst("^apache\s+syncope[\s_-]*", "");
        value = value.replaceFirst("^syncope[\s_-]*", "");
        value = value.replaceFirst("^release[\s_-]*", "");
        value = value.replace('_', '-').replace(' ', '-');
        value = value.replaceAll("(?<=\\d)\\.(?=(m|rc|alpha|beta)\\d*$)", "-");
        value = value.replaceAll("-+", "-");
        return value.replaceAll("^-|-$", "");
    }

    static int compareVersionNames(final String first, final String second) {
        VersionParts a = VersionParts.parse(first);
        VersionParts b = VersionParts.parse(second);
        int result = Integer.compare(a.major(), b.major());
        if (result == 0) result = Integer.compare(a.minor(), b.minor());
        if (result == 0) result = Integer.compare(a.patch(), b.patch());
        if (result == 0) result = Integer.compare(a.qualifierRank(), b.qualifierRank());
        if (result == 0) result = Integer.compare(a.qualifierNumber(), b.qualifierNumber());
        return result == 0 ? a.normalized().compareTo(b.normalized()) : result;
    }
}


record VersionParts(
        int major,
        int minor,
        int patch,
        int qualifierRank,
        int qualifierNumber,
        String normalized) {
    private static final Pattern VERSION_NUMBERS_PATTERN =
            Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(.*)$");


    static VersionParts parse(final String rawVersion) {
        String normalized = VersionUtils.canonicalVersion(rawVersion);
        Matcher matcher = VERSION_NUMBERS_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            return new VersionParts(0, 0, 0, 0, 0, normalized);
        }

        int major = parseInt(matcher.group(1));
        int minor = parseInt(matcher.group(2));
        int patch = parseInt(matcher.group(3));
        String qualifier = Optional.ofNullable(matcher.group(4)).orElse("")
                .replaceFirst("^-", "");

        /*
         * "incubating" descrive lo stato del progetto Apache, non rende
         * una release precedente a milestone o release candidate.
         * Lo rimuoviamo prima di classificare il vero qualificatore.
         */
        String lifecycleQualifier = qualifier
                .replaceAll("(?i)(^|-)incubating($|-)", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");

        int rank = 100;
        int number = 0;
        if (!lifecycleQualifier.isBlank()) {
            if (lifecycleQualifier.contains("snapshot")) {
                rank = 0;
            } else if (lifecycleQualifier.startsWith("alpha")) {
                rank = 20;
            } else if (lifecycleQualifier.startsWith("beta")) {
                rank = 30;
            } else if (lifecycleQualifier.startsWith("m")) {
                rank = 40;
            } else if (lifecycleQualifier.startsWith("rc")) {
                rank = 50;
            } else {
                rank = 60;
            }

            Matcher digits = Pattern.compile("(\\d+)")
                    .matcher(lifecycleQualifier);
            if (digits.find()) {
                number = parseInt(digits.group(1));
            }
        }

        return new VersionParts(major, minor, patch, rank, number, normalized);
    }

    private static int parseInt(final String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}


final class ProductionJavaPathFilter {
    private static final Set<String> EXCLUDED_SEGMENTS = Set.of(
            "test", "tests", "testfixtures", "integration-test", "integration-tests",
            "target", "build", "generated-sources", "generated-test-sources",
            "examples", "archetype-resources");

    private ProductionJavaPathFilter() {
    }

    static boolean isProductionJavaPath(final String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return false;
        String lower = rawPath.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".java")) return false;
        if (lower.contains("/src/test/") || lower.contains("/src/test-")
                || lower.contains("/src/it/") || lower.contains("/src/integration-test/")
                || lower.contains("/target/") || lower.contains("/generated-sources/")
                || lower.contains("/generated-test-sources/")) return false;
        String[] segments = lower.split("/");
        for (String segment : segments) {
            if (EXCLUDED_SEGMENTS.contains(segment)) return false;
        }
        String filename = segments.length == 0 ? lower : segments[segments.length - 1];
        return !filename.endsWith("test.java")
                && !filename.endsWith("tests.java")
                && !filename.endsWith("itcase.java");
    }
}


final class DatasetText {
    private DatasetText() {
    }

    static String joinVersions(final Collection<Release> releases) {
        return releases.stream()
                .sorted(Comparator.comparingInt(release -> release.sequence))
                .map(release -> release.version)
                .collect(Collectors.joining(" | "));
    }

    static String joinStrings(final Collection<String> values) {
        return values.stream().filter(Objects::nonNull).filter(value -> !value.isBlank())
                .collect(Collectors.joining(" | "));
    }

    static LocalDate dateOf(final OffsetDateTime value) {
        return value == null ? null : value.toLocalDate();
    }

    static LocalDate minDate(final Collection<GitCommit> commits) {
        return commits.stream().map(commit -> commit.committerDate)
                .filter(Objects::nonNull).map(OffsetDateTime::toLocalDate)
                .min(LocalDate::compareTo).orElse(null);
    }
}


final class FileSystemUtils {
    private FileSystemUtils() {
    }

    static void deleteRecursively(final Path path) throws IOException {
        if (path == null || !Files.exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(
                    final Path file,
                    final BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(
                    final Path directory,
                    final IOException exception) throws IOException {
                if (exception != null) throw exception;
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}


final class TypeNameUtils {
    private TypeNameUtils() {
    }

    static String simpleTypeName(final String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return "";
        }
        String value = rawType.replaceAll("<.*>", "")
                .replace("[]", "")
                .replace("? extends ", "")
                .replace("? super ", "")
                .trim();
        int dot = value.lastIndexOf('.');
        return dot >= 0 ? value.substring(dot + 1) : value;
    }
}


enum ConsistencyStatus {
    CONSISTENT,
    INCONSISTENT,
    NOT_FULLY_CHECKABLE
}


enum LabelingStatus {
    NOT_PROCESSED,
    USED,
    EXCLUDED,
    OUTSIDE_SELECTED_RELEASE_WINDOW
}


final class ReleaseDateOverride {
    final LocalDate effectiveDate;
    final String evidenceUrl;
    final String reason;

    ReleaseDateOverride(
            final LocalDate effectiveDate,
            final String evidenceUrl,
            final String reason) {
        this.effectiveDate = effectiveDate;
        this.evidenceUrl = evidenceUrl;
        this.reason = reason;
    }

    LocalDate effectiveDate() { return effectiveDate; }
    String evidenceUrl() { return evidenceUrl; }
    String reason() { return reason; }
}


final class JiraVersion {
    final String id;
    final String name;
    final String canonical;
    final boolean released;
    final boolean archived;
    final LocalDate releaseDate;
    final String description;

    JiraVersion(
            final String id,
            final String name,
            final String canonical,
            final boolean released,
            final boolean archived,
            final LocalDate releaseDate,
            final String description) {
        this.id = id;
        this.name = name;
        this.canonical = canonical;
        this.released = released;
        this.archived = archived;
        this.releaseDate = releaseDate;
        this.description = description;
    }

    String id() { return id; }
    String name() { return name; }
    String canonical() { return canonical; }
    boolean released() { return released; }
    boolean archived() { return archived; }
    LocalDate releaseDate() { return releaseDate; }
    String description() { return description; }
}


final class IssueRaw {
    final String id;
    final String key;
    final String summary;
    final String status;
    final String resolution;
    final String priority;
    final OffsetDateTime createDate;
    final OffsetDateTime closedDate;
    final List<String> affectedVersionsRaw;
    final List<String> fixedVersionsRaw;

    IssueRaw(
            final String id,
            final String key,
            final String summary,
            final String status,
            final String resolution,
            final String priority,
            final OffsetDateTime createDate,
            final OffsetDateTime closedDate,
            final List<String> affectedVersionsRaw,
            final List<String> fixedVersionsRaw) {
        this.id = id;
        this.key = key;
        this.summary = summary;
        this.status = status;
        this.resolution = resolution;
        this.priority = priority;
        this.createDate = createDate;
        this.closedDate = closedDate;
        this.affectedVersionsRaw = List.copyOf(affectedVersionsRaw);
        this.fixedVersionsRaw = List.copyOf(fixedVersionsRaw);
    }

    String id() { return id; }
    String key() { return key; }
    String summary() { return summary; }
    String status() { return status; }
    String resolution() { return resolution; }
    String priority() { return priority; }
    OffsetDateTime createDate() { return createDate; }
    OffsetDateTime closedDate() { return closedDate; }
    List<String> affectedVersionsRaw() { return affectedVersionsRaw; }
    List<String> fixedVersionsRaw() { return fixedVersionsRaw; }
}


final class FileChange {
    final String status;
    final String oldPath;
    final String newPath;

    FileChange(final String status, final String oldPath, final String newPath) {
        this.status = status;
        this.oldPath = oldPath;
        this.newPath = newPath;
    }

    String status() { return status; }
    String oldPath() { return oldPath; }
    String newPath() { return newPath; }
}


final class ReleaseAnomaly {
    final String type;
    final String severity;
    final String version;
    final String canonicalVersion;
    final String relatedVersion;
    final LocalDate jiraReleaseDate;
    final LocalDate effectiveReleaseDate;
    final LocalDate tagCommitDate;
    final String details;
    final String evidenceUrl;

    ReleaseAnomaly(
            final String type,
            final String severity,
            final String version,
            final String canonicalVersion,
            final String relatedVersion,
            final LocalDate jiraReleaseDate,
            final LocalDate effectiveReleaseDate,
            final LocalDate tagCommitDate,
            final String details,
            final String evidenceUrl) {
        this.type = type;
        this.severity = severity;
        this.version = version;
        this.canonicalVersion = canonicalVersion;
        this.relatedVersion = relatedVersion;
        this.jiraReleaseDate = jiraReleaseDate;
        this.effectiveReleaseDate = effectiveReleaseDate;
        this.tagCommitDate = tagCommitDate;
        this.details = details;
        this.evidenceUrl = evidenceUrl;
    }

    String type() { return type; }
    String severity() { return severity; }
    String version() { return version; }
    String canonicalVersion() { return canonicalVersion; }
    String relatedVersion() { return relatedVersion; }
    LocalDate jiraReleaseDate() { return jiraReleaseDate; }
    LocalDate effectiveReleaseDate() { return effectiveReleaseDate; }
    LocalDate tagCommitDate() { return tagCommitDate; }
    String details() { return details; }
    String evidenceUrl() { return evidenceUrl; }
}


final class Release {

    final String jiraId;
    final String version;
    final String canonicalVersion;
    final LocalDate jiraReleaseDate;
    final LocalDate releaseDate;
    final String releaseDateSource;
    final String releaseDateCorrectionReason;
    final String releaseDateEvidenceUrl;
    final String gitTag;
    final String tagCommitHash;
    final LocalDate tagCommitDate;
    final boolean archived;
    int sequence;
    boolean selectedForDataset;
    int productionJavaFileCount;

    Release(
            final String jiraId,
            final String version,
            final String canonicalVersion,
            final LocalDate jiraReleaseDate,
            final LocalDate releaseDate,
            final String releaseDateSource,
            final String releaseDateCorrectionReason,
            final String releaseDateEvidenceUrl,
            final String gitTag,
            final String tagCommitHash,
            final LocalDate tagCommitDate,
            final boolean archived) {
        this.jiraId = jiraId;
        this.version = version;
        this.canonicalVersion = canonicalVersion;
        this.jiraReleaseDate = jiraReleaseDate;
        this.releaseDate = releaseDate;
        this.releaseDateSource = releaseDateSource;
        this.releaseDateCorrectionReason = releaseDateCorrectionReason;
        this.releaseDateEvidenceUrl = releaseDateEvidenceUrl;
        this.gitTag = gitTag;
        this.tagCommitHash = tagCommitHash;
        this.tagCommitDate = tagCommitDate;
        this.archived = archived;
    }

    boolean releaseDateCorrected() {
        return !Objects.equals(jiraReleaseDate, releaseDate);
    }
}


final class GitCommit {

    final String hash;
    final OffsetDateTime authorDate;
    final OffsetDateTime committerDate;
    final String subject;
    final List<FileChange> fileChanges = new ArrayList<>();
    final List<String> violations = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();
    boolean temporalValid;

    GitCommit(
            final String hash,
            final OffsetDateTime authorDate,
            final OffsetDateTime committerDate,
            final String subject) {
        this.hash = hash;
        this.authorDate = authorDate;
        this.committerDate = committerDate;
        this.subject = subject;
    }

    GitCommit copy() {
        GitCommit copy = new GitCommit(hash, authorDate, committerDate, subject);
        copy.fileChanges.addAll(fileChanges);
        return copy;
    }

    void evaluateFor(final Ticket ticket) {
        violations.clear();
        warnings.clear();
        LocalDate commitDate = DatasetText.dateOf(committerDate);
        LocalDate createDate = DatasetText.dateOf(ticket.issue.createDate);

        if (commitDate == null) {
            violations.add("MISSING_COMMITTER_DATE");
        }
        if (createDate != null && commitDate != null
                && commitDate.isBefore(createDate)) {
            violations.add("FIX_COMMIT_BEFORE_TICKET_CREATION");
        }
        if (ticket.openingVersion != null && commitDate != null
                && commitDate.isBefore(ticket.openingVersion.releaseDate)) {
            violations.add("FIX_COMMIT_BEFORE_OPENING_VERSION");
        }
        if (ticket.fixedVersion != null && commitDate != null
                && commitDate.isAfter(ticket.fixedVersion.releaseDate)) {
            violations.add("FIX_COMMIT_AFTER_FIXED_VERSION");
        }
        if (productionJavaPaths().isEmpty()) {
            warnings.add("NO_PRODUCTION_JAVA_FILES");
        }

        temporalValid = violations.isEmpty();
    }

    Set<String> productionJavaPaths() {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (FileChange change : fileChanges) {
            if (ProductionJavaPathFilter.isProductionJavaPath(change.oldPath())) {
                paths.add(change.oldPath());
            }
            if (ProductionJavaPathFilter.isProductionJavaPath(change.newPath())) {
                paths.add(change.newPath());
            }
        }
        return paths;
    }
}


final class Ticket {

    final IssueRaw issue;
    final ReleaseCatalog releaseCatalog;
    final List<Release> rawAffectedRecognized = new ArrayList<>();
    final List<Release> rawFixedRecognized = new ArrayList<>();
    final List<Release> affectedVersions = new ArrayList<>();
    final List<String> unrecognizedAffectedVersions = new ArrayList<>();
    final List<String> unrecognizedFixedVersions = new ArrayList<>();
    final List<GitCommit> commitCandidates = new ArrayList<>();
    final List<GitCommit> validCommits = new ArrayList<>();
    final List<String> violations = new ArrayList<>();
    final List<String> dataGaps = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();

    Release openingVersion;
    Release fixedVersion;
    Release injectedVersion;
    String injectedVersionSource = "";
    Double proportionUsed;
    int totalProportionObservationCount;
    ConsistencyStatus consistencyStatus = ConsistencyStatus.NOT_FULLY_CHECKABLE;
    LabelingStatus labelingStatus = LabelingStatus.NOT_PROCESSED;
    int labeledClassReleaseRows;

    Ticket(final IssueRaw issue, final ReleaseCatalog releaseCatalog) {
        this.issue = issue;
        this.releaseCatalog = releaseCatalog;
    }

    void evaluateCommitCandidates() {
        validCommits.clear();
        for (GitCommit commit : commitCandidates) {
            commit.evaluateFor(this);
            if (commit.temporalValid) {
                validCommits.add(commit);
            }
        }
    }



    LocalDate fixOrderingDate() {
        LocalDate commitDate = DatasetText.minDate(validCommits);
        if (commitDate != null) {
            return commitDate;
        }
        if (issue.closedDate != null) {
            return issue.closedDate.toLocalDate();
        }
        return fixedVersion == null ? null : fixedVersion.releaseDate;
    }

    String blockingReasonForLabeling() {
        if (consistencyStatus == ConsistencyStatus.INCONSISTENT) {
            return "INCONSISTENT_LIFECYCLE";
        }
        if (openingVersion == null) {
            return "NO_OPENING_VERSION";
        }
        if (fixedVersion == null) {
            return "NO_FIXED_VERSION";
        }
        if (injectedVersion == null) {
            return "IV_NOT_ESTIMABLE";
        }
        if (validCommits.isEmpty()) {
            return commitCandidates.isEmpty()
                    ? "NO_FIX_COMMIT"
                    : "FIX_COMMIT_OUTSIDE_VALID_INTERVAL";
        }
        return null;
    }

    void addViolation(final String value) {
        if (!violations.contains(value)) {
            violations.add(value);
        }
    }

    void addGap(final String value) {
        if (!dataGaps.contains(value)) {
            dataGaps.add(value);
        }
    }

    void addWarning(final String value) {
        if (!warnings.contains(value)) {
            warnings.add(value);
        }
    }
}


final class FileProcessMetrics {
    int locTouched;
    int revisions;
    int defectFixes;
    int authors;
    int locAdded;
    int maxLocAdded;
    double averageLocAdded;
    int maxChurn;
    double averageChurn;
    int changeSetSize;
    int maxChangeSet;
    double averageChangeSet;
    double age;
    double weightedAge;
    double averageChangeInterval;

    static FileProcessMetrics empty() {
        return new FileProcessMetrics();
    }
}


record PmdSmellSnapshot(
        Integer violationCount,
        Integer ruleTypeCount,
        String rules,
        String status,
        String warning) {

    static final String STATUS_OK = "OK";
    static final String STATUS_ERROR = "ERROR";
    static final String STATUS_NO_PREVIOUS_SOURCE = "NO_PREVIOUS_SOURCE";

    PmdSmellSnapshot {
        rules = rules == null ? "" : rules;
        status = status == null ? STATUS_ERROR : status;
        warning = warning == null ? "" : warning;
        if (violationCount != null && violationCount < 0) {
            throw new IllegalArgumentException(
                    "Il numero di violazioni PMD non può essere negativo.");
        }
        if (ruleTypeCount != null && ruleTypeCount < 0) {
            throw new IllegalArgumentException(
                    "Il numero di tipi di regola PMD non può essere negativo.");
        }
    }

    static PmdSmellSnapshot success(final Map<String, Integer> ruleCounts) {
        Map<String, Integer> sorted = new java.util.TreeMap<>();
        if (ruleCounts != null) {
            ruleCounts.forEach((rule, count) -> {
                if (rule != null && !rule.isBlank() && count != null && count > 0) {
                    sorted.put(rule, count);
                }
            });
        }
        int total = sorted.values().stream().mapToInt(Integer::intValue).sum();
        String summary = sorted.entrySet().stream()
                .map(entry -> entry.getKey() + "(" + entry.getValue() + ")")
                .collect(Collectors.joining(" | "));
        return new PmdSmellSnapshot(
                total,
                sorted.size(),
                summary,
                STATUS_OK,
                "");
    }

    static PmdSmellSnapshot error(final String warning) {
        return new PmdSmellSnapshot(
                null,
                null,
                "",
                STATUS_ERROR,
                warning == null ? "PMD_ANALYSIS_ERROR" : warning);
    }

    static PmdSmellSnapshot noPreviousSource() {
        return new PmdSmellSnapshot(
                0,
                0,
                "",
                STATUS_NO_PREVIOUS_SOURCE,
                "");
    }
}


final class ClassMetrics {

    String project;
    Release release;
    String className;
    String classPath;

    // Product metrics calculated on the current release snapshot.
    int loc;
    int cloc;
    int wmc;
    int npm;

    // Process metrics calculated from Git history reachable from the release tag.
    int locTouched;
    int revisions;
    int defectFixes;
    int authors;
    int locAdded;
    int maxLocAdded;
    double averageLocAdded;
    int maxChurn;
    double averageChurn;
    int changeSetSize;
    int maxChangeSet;
    double averageChangeSet;
    double age;
    double weightedAge;
    double averageChangeInterval;

    // PMD features exposed in the dataset come from the previous selected release.
    String smellSourceRelease = "";
    Integer nSmells = 0;
    Integer nPmdRuleTypes = 0;
    String pmdRules = "";
    String pmdAnalysisStatus = PmdSmellSnapshot.STATUS_NO_PREVIOUS_SOURCE;
    String pmdAnalysisWarning = "";

    // Current-release PMD result is internal and feeds the next release.
    PmdSmellSnapshot detectedPmdSmells = PmdSmellSnapshot.error("PMD_NOT_EXECUTED");

    boolean buggy;
    final Set<String> bugTickets = new TreeSet<>();
    final Set<String> fixCommits = new TreeSet<>();
    String consistencyStatus = "NO_BUG_LINK";
    String analysisWarning = "";

    void applyProcessMetrics(final FileProcessMetrics process) {
        FileProcessMetrics source = process == null ? FileProcessMetrics.empty() : process;
        locTouched = source.locTouched;
        revisions = source.revisions;
        defectFixes = source.defectFixes;
        authors = source.authors;
        locAdded = source.locAdded;
        maxLocAdded = source.maxLocAdded;
        averageLocAdded = source.averageLocAdded;
        maxChurn = source.maxChurn;
        averageChurn = source.averageChurn;
        changeSetSize = source.changeSetSize;
        maxChangeSet = source.maxChangeSet;
        averageChangeSet = source.averageChangeSet;
        age = source.age;
        weightedAge = source.weightedAge;
        averageChangeInterval = source.averageChangeInterval;
    }

    void applyPreviousSmells(
            final String sourceRelease,
            final PmdSmellSnapshot smells) {
        PmdSmellSnapshot source = smells == null
                ? PmdSmellSnapshot.noPreviousSource()
                : smells;
        smellSourceRelease = smells == null || sourceRelease == null
                ? ""
                : sourceRelease;
        nSmells = source.violationCount();
        nPmdRuleTypes = source.ruleTypeCount();
        pmdRules = source.rules();
        pmdAnalysisStatus = source.status();
        pmdAnalysisWarning = source.warning();
    }

    PmdSmellSnapshot currentSmells() {
        return detectedPmdSmells;
    }
}


final class ExcludedTicket {
    final String ticketId;
    final String reason;
    final String consistencyStatus;
    final String violations;
    final String dataGaps;
    final String warnings;

    ExcludedTicket(
            final String ticketId,
            final String reason,
            final String consistencyStatus,
            final String violations,
            final String dataGaps,
            final String warnings) {
        this.ticketId = ticketId;
        this.reason = reason;
        this.consistencyStatus = consistencyStatus;
        this.violations = violations;
        this.dataGaps = dataGaps;
        this.warnings = warnings;
    }

    static ExcludedTicket from(final Ticket ticket, final String reason) {
        return new ExcludedTicket(
                ticket.issue.key,
                reason,
                ticket.consistencyStatus.name(),
                DatasetText.joinStrings(ticket.violations),
                DatasetText.joinStrings(ticket.dataGaps),
                DatasetText.joinStrings(ticket.warnings));
    }
}

