package it.university.buggyprediction.milestone1;


import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PipelineContext {
    private final Milestone1Config config;
    private final Instant startedAt = Instant.now();

    private ReleaseCatalog releaseCatalog;
    private List<IssueRaw> rawIssues = List.of();
    private Map<String, List<GitCommit>> commitsByIssue = Map.of();
    private List<Ticket> tickets = List.of();
    private List<Release> selectedReleases = List.of();
    private List<ClassMetrics> datasetRows = List.of();
    private List<ExcludedTicket> excludedTickets = List.of();
    private Instant completedAt;
    private String repositoryHead = "";

    PipelineContext(final Milestone1Config config) {
        this.config = config;
    }

    Milestone1Config config() { return config; }
    Instant startedAt() { return startedAt; }
    Instant completedAt() { return completedAt; }
    void completedAt(final Instant value) { completedAt = value; }
    String repositoryHead() { return repositoryHead; }
    void repositoryHead(final String value) { repositoryHead = value; }

    ReleaseCatalog releaseCatalog() { return releaseCatalog; }
    void releaseCatalog(final ReleaseCatalog value) { releaseCatalog = value; }
    ReleaseCatalog requireReleaseCatalog() {
        if (releaseCatalog == null) throw missing("release");
        return releaseCatalog;
    }

    List<IssueRaw> rawIssues() { return rawIssues; }
    void rawIssues(final List<IssueRaw> value) { rawIssues = List.copyOf(value); }
    List<IssueRaw> requireRawIssues() {
        if (rawIssues.isEmpty()) throw missing("tickets");
        return rawIssues;
    }

    Map<String, List<GitCommit>> commitsByIssue() { return commitsByIssue; }
    void commitsByIssue(final Map<String, List<GitCommit>> value) {
        commitsByIssue = new LinkedHashMap<>(value);
    }
    Map<String, List<GitCommit>> requireCommitsByIssue() {
        if (commitsByIssue.isEmpty()) throw missing("commits");
        return commitsByIssue;
    }

    List<Ticket> tickets() { return tickets; }
    void tickets(final List<Ticket> value) { tickets = new ArrayList<>(value); }
    List<Ticket> requireTickets() {
        if (tickets.isEmpty()) throw missing("lifecycle");
        return tickets;
    }

    List<Release> selectedReleases() { return selectedReleases; }
    void selectedReleases(final List<Release> value) { selectedReleases = new ArrayList<>(value); }
    List<Release> requireSelectedReleases() {
        if (selectedReleases.isEmpty()) throw missing("metrics");
        return selectedReleases;
    }

    List<ClassMetrics> datasetRows() { return datasetRows; }
    void datasetRows(final List<ClassMetrics> value) { datasetRows = new ArrayList<>(value); }
    List<ClassMetrics> requireDatasetRows() {
        if (datasetRows.isEmpty()) throw missing("metrics");
        return datasetRows;
    }

    List<ExcludedTicket> excludedTickets() { return excludedTickets; }
    void excludedTickets(final List<ExcludedTicket> value) {
        excludedTickets = new ArrayList<>(value);
    }

    private static IllegalStateException missing(final String step) {
        return new IllegalStateException(
                "Dati non disponibili: eseguire prima la fase '" + step + "'.");
    }
}
