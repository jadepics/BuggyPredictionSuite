package it.university.buggyprediction.milestone1;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


final class LabelingStep implements MilestoneStep {
    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(LabelingStep.class.getName());

    @Override
    public String id() {
        return "labeling";
    }

    @Override
    public String description() {
        return "Propagazione della bugginess e labeling Yes/No";
    }

    @Override
    public void execute(final PipelineContext context) {
        java.util.List<ExcludedTicket> excluded = new BugLabeler(
                context.requireSelectedReleases(), context.requireDatasetRows())
                .apply(context.requireTickets());
        context.excludedTickets(excluded);

        long buggyRows = context.requireDatasetRows().stream()
                .filter(row -> row.buggy)
                .count();
        LOGGER.log(java.util.logging.Level.INFO, "Righe buggy=Yes: {0}", buggyRows);
        LOGGER.log(java.util.logging.Level.INFO,
                "Ticket esclusi dal labeling: {0}", excluded.size());
    }
}

final class BugLabeler {

    private final List<Release> selectedReleases;
    private final List<ClassMetrics> rows;
    private final Map<Integer, Map<String, ClassMetrics>> rowsByReleaseAndPath;

    BugLabeler(
            final List<Release> selectedReleases,
            final List<ClassMetrics> rows) {
        this.selectedReleases = selectedReleases;
        this.rows = rows;
        this.rowsByReleaseAndPath = new HashMap<>();

        for (ClassMetrics row : rows) {
            rowsByReleaseAndPath
                    .computeIfAbsent(row.release.sequence, ignored -> new HashMap<>())
                    .put(row.classPath, row);
        }
    }

    List<ExcludedTicket> apply(final List<Ticket> tickets) {
        List<ExcludedTicket> excluded = new ArrayList<>();
        int lastSelectedSequence = selectedReleases.getLast().sequence;

        for (Ticket ticket : tickets) {
            String blockingReason = ticket.blockingReasonForLabeling();
            if (blockingReason != null) {
                excluded.add(ExcludedTicket.from(ticket, blockingReason));
                ticket.labelingStatus = LabelingStatus.EXCLUDED;
                continue;
            }

            if (ticket.injectedVersion.sequence > lastSelectedSequence) {
                ticket.labelingStatus = LabelingStatus.OUTSIDE_SELECTED_RELEASE_WINDOW;
                continue;
            }

            Set<String> changedJavaPaths = ticket.validCommits.stream()
                    .flatMap(commit -> commit.productionJavaPaths().stream())
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            if (changedJavaPaths.isEmpty()) {
                excluded.add(ExcludedTicket.from(
                        ticket,
                        "NO_PRODUCTION_JAVA_FILES_IN_VALID_COMMITS"));
                ticket.labelingStatus = LabelingStatus.EXCLUDED;
                continue;
            }

            int matchedRows = 0;
            for (Release release : selectedReleases) {
                if (release.sequence < ticket.injectedVersion.sequence
                        || release.sequence >= ticket.fixedVersion.sequence) {
                    continue;
                }

                Map<String, ClassMetrics> metricsByPath = rowsByReleaseAndPath
                        .getOrDefault(release.sequence, Map.of());

                for (String path : changedJavaPaths) {
                    ClassMetrics row = metricsByPath.get(path);
                    if (row != null) {
                        row.buggy = true;
                        row.bugTickets.add(ticket.issue.key);
                        ticket.validCommits.stream()
                                .filter(commit -> commit.productionJavaPaths().contains(path))
                                .forEach(commit -> row.fixCommits.add(commit.hash));
                        matchedRows++;
                    }
                }
            }

            if (matchedRows == 0) {
                excluded.add(ExcludedTicket.from(
                        ticket,
                        "NO_MATCHING_CLASS_IN_SELECTED_RELEASES"));
                ticket.labelingStatus = LabelingStatus.EXCLUDED;
            } else {
                ticket.labelingStatus = LabelingStatus.USED;
                ticket.labeledClassReleaseRows = matchedRows;
            }
        }

        for (ClassMetrics row : rows) {
            row.consistencyStatus = row.buggy ? "CONSISTENT_BUG_LINK" : "NO_BUG_LINK";
        }
        return excluded;
    }
}

