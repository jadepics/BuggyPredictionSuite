package it.university.buggyprediction.milestone1;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import static it.university.buggyprediction.milestone1.Milestone1Constants.RELEASE_DATE_OVERRIDES;


final class ReleaseStep implements MilestoneStep {
    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(ReleaseStep.class.getName());

    private final JiraClient jiraClient;
    private final GitHubService gitHubService;

    ReleaseStep(final JiraClient jiraClient, final GitHubService gitHubService) {
        this.jiraClient = jiraClient;
        this.gitHubService = gitHubService;
    }

    @Override
    public String id() {
        return "release";
    }

    @Override
    public String description() {
        return "Recupero release JIRA, tag GitHub e anomalie";
    }

    @Override
    public void execute(final PipelineContext context) throws Exception {
        java.util.List<JiraVersion> jiraVersions = jiraClient.fetchVersions();
        java.util.Map<String, String> tags = gitHubService.collectTagsByCanonicalVersion();
        ReleaseCatalog catalog = ReleaseCatalog.build(jiraVersions, tags,
                new GitClient(gitHubService.mirrorPath(), new IssueKeyExtractor()));
        context.releaseCatalog(catalog);

        LOGGER.log(java.util.logging.Level.INFO,
                "Release JIRA con data: {0}", catalog.allReleases().size());
        LOGGER.log(java.util.logging.Level.INFO,
                "Release utilizzabili con tag Git: {0}", catalog.taggedReleases().size());
        LOGGER.log(java.util.logging.Level.INFO,
                "Anomalie release rilevate: {0}", catalog.releaseAnomalies().size());
    }
}

final class ReleaseCatalog {

    private final List<Release> allReleases;
    private final List<Release> taggedReleases;
    private final Map<String, Release> byCanonicalVersion;
    private final List<ReleaseAnomaly> releaseAnomalies;

    ReleaseCatalog(
            final List<Release> allReleases,
            final List<Release> taggedReleases,
            final Map<String, Release> byCanonicalVersion,
            final List<ReleaseAnomaly> releaseAnomalies) {
        this.allReleases = allReleases;
        this.taggedReleases = taggedReleases;
        this.byCanonicalVersion = byCanonicalVersion;
        this.releaseAnomalies = releaseAnomalies;
    }

    static ReleaseCatalog build(
            final List<JiraVersion> jiraVersions,
            final Map<String, String> tagsByCanonical,
            final GitClient git) throws IOException, InterruptedException {
        Map<String, JiraVersion> jiraByCanonical = selectReleasedJiraVersions(jiraVersions);
        List<ReleaseAnomaly> anomalies = new ArrayList<>();
        List<Release> releases = createReleases(jiraByCanonical, tagsByCanonical, git, anomalies);
        sortAndIndex(releases);
        detectSemanticDateInversions(releases, anomalies);
        detectTagDateWarnings(releases, anomalies);
        List<Release> tagged = releases.stream()
                .filter(release -> release.gitTag != null)
                .collect(Collectors.toCollection(ArrayList::new));
        return new ReleaseCatalog(releases, tagged, indexByCanonical(releases), anomalies);
    }

    private static Map<String, JiraVersion> selectReleasedJiraVersions(
            final List<JiraVersion> jiraVersions) {
        Map<String, JiraVersion> selected = new LinkedHashMap<>();
        for (JiraVersion version : jiraVersions) {
            if (!version.released || version.releaseDate == null || version.canonical.isBlank()) {
                continue;
            }
            JiraVersion previous = selected.get(version.canonical);
            if (previous == null || (previous.archived && !version.archived)
                    || version.releaseDate.isAfter(previous.releaseDate)) {
                selected.put(version.canonical, version);
            }
        }
        return selected;
    }

    private static List<Release> createReleases(
            final Map<String, JiraVersion> jiraByCanonical,
            final Map<String, String> tagsByCanonical,
            final GitClient git,
            final List<ReleaseAnomaly> anomalies)
            throws IOException, InterruptedException {
        List<Release> releases = new ArrayList<>();
        for (JiraVersion jira : jiraByCanonical.values()) {
            TagEvidence tag = resolveTag(tagsByCanonical.get(jira.canonical), git);
            ReleaseDateEvidence date = resolveReleaseDate(jira, tag.commitDate(), anomalies);
            releases.add(new Release(
                    jira.id, jira.name, jira.canonical, jira.releaseDate,
                    date.effectiveDate(), date.source(), date.reason(), date.evidenceUrl(),
                    tag.tag(), tag.commitHash(), tag.commitDate(), jira.archived));
        }
        return releases;
    }

    private static TagEvidence resolveTag(final String candidate, final GitClient git)
            throws IOException, InterruptedException {
        if (candidate == null) return new TagEvidence(null, null, null);
        String hash = git.commitForTag(candidate);
        return hash.isBlank()
                ? new TagEvidence(null, null, null)
                : new TagEvidence(candidate, hash, git.commitDateForTag(candidate));
    }

    private static ReleaseDateEvidence resolveReleaseDate(
            final JiraVersion jira,
            final LocalDate tagCommitDate,
            final List<ReleaseAnomaly> anomalies) {
        ReleaseDateOverride override = RELEASE_DATE_OVERRIDES.get(jira.canonical);
        if (override == null || override.effectiveDate().equals(jira.releaseDate)) {
            return new ReleaseDateEvidence(jira.releaseDate, "JIRA", "", "");
        }
        anomalies.add(new ReleaseAnomaly(
                "JIRA_OFFICIAL_DATE_CONFLICT", "CORRECTED", jira.name, jira.canonical, "",
                jira.releaseDate, override.effectiveDate(), tagCommitDate,
                override.reason(), override.evidenceUrl()));
        return new ReleaseDateEvidence(
                override.effectiveDate(), "OFFICIAL_APACHE_OVERRIDE",
                override.reason(), override.evidenceUrl());
    }

    private static void sortAndIndex(final List<Release> releases) {
        releases.sort(Comparator
                .comparing((Release release) -> release.releaseDate)
                .thenComparing(release -> release.version, VersionUtils::compareVersionNames));
        for (int index = 0; index < releases.size(); index++) {
            releases.get(index).sequence = index;
        }
    }

    private static Map<String, Release> indexByCanonical(final List<Release> releases) {
        Map<String, Release> result = new LinkedHashMap<>();
        releases.forEach(release -> result.put(release.canonicalVersion, release));
        return result;
    }

    private static void detectSemanticDateInversions(
            final List<Release> releases,
            final List<ReleaseAnomaly> anomalies) {

        Map<String, List<Release>> byReleaseLine = new TreeMap<>();
        for (Release release : releases) {
            VersionParts parts = VersionParts.parse(release.canonicalVersion);
            String line = parts.major() + "." + parts.minor();
            byReleaseLine
                    .computeIfAbsent(line, ignored -> new ArrayList<>())
                    .add(release);
        }

        for (Map.Entry<String, List<Release>> entry : byReleaseLine.entrySet()) {
            List<Release> semanticOrder = new ArrayList<>(entry.getValue());
            semanticOrder.sort((first, second) ->
                    VersionUtils.compareVersionNames(first.version, second.version));

            Release previous = null;
            for (Release current : semanticOrder) {
                if (previous != null) {
                    if (current.jiraReleaseDate.isBefore(previous.jiraReleaseDate)) {
                        anomalies.add(new ReleaseAnomaly(
                                "JIRA_SEMANTIC_DATE_INVERSION",
                                "WARNING",
                                current.version,
                                current.canonicalVersion,
                                previous.version,
                                current.jiraReleaseDate,
                                current.releaseDate,
                                current.tagCommitDate,
                                "Nella linea " + entry.getKey()
                                        + " la versione semanticamente successiva "
                                        + current.version
                                        + " ha data JIRA precedente a "
                                        + previous.version + ".",
                                current.releaseDateEvidenceUrl));
                    }

                    if (current.releaseDate.isBefore(previous.releaseDate)) {
                        anomalies.add(new ReleaseAnomaly(
                                "EFFECTIVE_SEMANTIC_DATE_INVERSION",
                                "BLOCKING",
                                current.version,
                                current.canonicalVersion,
                                previous.version,
                                current.jiraReleaseDate,
                                current.releaseDate,
                                current.tagCommitDate,
                                "Anche dopo le correzioni documentate, "
                                        + current.version
                                        + " risulta precedente a "
                                        + previous.version + ".",
                                current.releaseDateEvidenceUrl));
                    }
                }
                previous = current;
            }
        }
    }

    private static void detectTagDateWarnings(
            final List<Release> releases,
            final List<ReleaseAnomaly> anomalies) {
        for (Release release : releases) {
            if (release.tagCommitDate != null
                    && release.tagCommitDate.isAfter(release.releaseDate)) {
                anomalies.add(new ReleaseAnomaly(
                        "TAG_COMMIT_AFTER_EFFECTIVE_RELEASE_DATE",
                        "WARNING",
                        release.version,
                        release.canonicalVersion,
                        "",
                        release.jiraReleaseDate,
                        release.releaseDate,
                        release.tagCommitDate,
                        "Il commit puntato dal tag è datato "
                                + release.tagCommitDate
                                + ", dopo la data effettiva di release "
                                + release.releaseDate
                                + ". Verificare timezone o metadati storici.",
                        release.releaseDateEvidenceUrl));
            }
        }
    }

    List<Release> allReleases() {
        return Collections.unmodifiableList(allReleases);
    }

    List<Release> taggedReleases() {
        return Collections.unmodifiableList(taggedReleases);
    }

    List<ReleaseAnomaly> releaseAnomalies() {
        return Collections.unmodifiableList(releaseAnomalies);
    }

    Release recognize(final String rawName) {
        return byCanonicalVersion.get(VersionUtils.canonicalVersion(rawName));
    }

    Release openingVersion(final LocalDate createDate) {
        if (createDate == null) {
            return null;
        }

        Release result = null;
        for (Release release : allReleases) {
            if (!release.releaseDate.isAfter(createDate)) {
                result = release;
            } else {
                break;
            }
        }
        return result;
    }

    List<Release> selectFirstFraction(
            final double fraction,
            final Integer optionalMaximum) {
        int count = (int) Math.ceil(taggedReleases.size() * fraction);
        count = Math.max(1, Math.min(count, taggedReleases.size()));
        if (optionalMaximum != null) {
            count = Math.min(count, optionalMaximum);
        }

        List<Release> result = new ArrayList<>(taggedReleases.subList(0, count));
        Set<Release> selected = new HashSet<>(result);
        for (Release release : allReleases) {
            release.selectedForDataset = selected.contains(release);
        }
        return result;
    }

    Release bySequence(final int sequence) {
        if (sequence < 0 || sequence >= allReleases.size()) {
            return null;
        }
        return allReleases.get(sequence);
    }
    private record TagEvidence(String tag, String commitHash, LocalDate commitDate) {
    }

    private record ReleaseDateEvidence(
            LocalDate effectiveDate,
            String source,
            String reason,
            String evidenceUrl) {
    }
}

