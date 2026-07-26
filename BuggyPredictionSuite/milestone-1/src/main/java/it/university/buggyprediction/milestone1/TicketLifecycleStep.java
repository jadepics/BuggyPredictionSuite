package it.university.buggyprediction.milestone1;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import static it.university.buggyprediction.milestone1.DatasetText.dateOf;


final class TicketLifecycleStep implements MilestoneStep {
    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(TicketLifecycleStep.class.getName());

    @Override
    public String id() {
        return "lifecycle";
    }

    @Override
    public String description() {
        return "Calcolo OV, AF, FV, IV, Proportion e consistenza";
    }

    @Override
    public void execute(final PipelineContext context) {
        ReleaseCatalog catalog = context.requireReleaseCatalog();
        TicketLifecycleBuilder builder = new TicketLifecycleBuilder(catalog);
        java.util.List<Ticket> tickets = context.requireRawIssues().stream()
                .map(builder::buildPreliminary)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));

        java.util.Map<String, java.util.List<GitCommit>> commits = context.requireCommitsByIssue();
        for (Ticket ticket : tickets) {
            ticket.commitCandidates.addAll(commits.getOrDefault(ticket.issue.key(), java.util.List.of()));
            ticket.evaluateCommitCandidates();
        }

        new ProportionEstimator(catalog, context.config().proportionTrace()).estimate(tickets);
        LifecycleValidator validator = new LifecycleValidator();
        tickets.forEach(validator::validate);
        context.tickets(tickets);

        long consistent = tickets.stream()
                .filter(ticket -> ticket.consistencyStatus == ConsistencyStatus.CONSISTENT)
                .count();
        LOGGER.info(() -> "Ticket consistenti dopo i controlli: "
                + consistent + "/" + tickets.size());
    }
}

final class TicketLifecycleBuilder {
    private final ReleaseCatalog catalog;

    TicketLifecycleBuilder(final ReleaseCatalog catalog) {
        this.catalog = catalog;
    }

    Ticket buildPreliminary(final IssueRaw issue) {
        Ticket ticket = new Ticket(issue, catalog);
        ticket.openingVersion = catalog.openingVersion(dateOf(issue.createDate));

        if (ticket.openingVersion == null) {
            ticket.dataGaps.add("OPENING_VERSION_NOT_FOUND");
        }

        ticket.rawAffectedRecognized.addAll(recognizeVersions(
                issue.affectedVersionsRaw,
                catalog,
                ticket.unrecognizedAffectedVersions));

        ticket.rawFixedRecognized.addAll(recognizeVersions(
                issue.fixedVersionsRaw,
                catalog,
                ticket.unrecognizedFixedVersions));

        if (!ticket.unrecognizedAffectedVersions.isEmpty()) {
            ticket.dataGaps.add("UNRECOGNIZED_AFFECTED_VERSION");
        }
        if (!ticket.unrecognizedFixedVersions.isEmpty()) {
            ticket.dataGaps.add("UNRECOGNIZED_FIXED_VERSION");
        }

        LinkedHashSet<Release> effectiveAffected = new LinkedHashSet<>();
        effectiveAffected.addAll(ticket.rawAffectedRecognized);

        if (ticket.rawFixedRecognized.isEmpty()) {
            ticket.affectedVersions.addAll(effectiveAffected.stream()
                    .sorted(Comparator.comparingInt(release -> release.sequence))
                    .toList());
            if (!ticket.affectedVersions.isEmpty()) {
                ticket.injectedVersion = ticket.affectedVersions.getFirst();
                ticket.injectedVersionSource = "AFFECTED_VERSION";
            }
            ticket.dataGaps.add("FIXED_VERSION_NOT_FOUND");
            return ticket;
        }

        ticket.rawFixedRecognized.sort(Comparator.comparingInt(release -> release.sequence));
        ticket.fixedVersion = ticket.rawFixedRecognized.getLast();

        if (ticket.rawFixedRecognized.size() > 1) {
            effectiveAffected.addAll(
                    ticket.rawFixedRecognized.subList(
                            0,
                            ticket.rawFixedRecognized.size() - 1));
        }

        ticket.affectedVersions.addAll(effectiveAffected.stream()
                .sorted(Comparator.comparingInt(release -> release.sequence))
                .toList());

        if (!ticket.affectedVersions.isEmpty()) {
            ticket.injectedVersion = ticket.affectedVersions.getFirst();
            ticket.injectedVersionSource = "AFFECTED_VERSION";
        }

        return ticket;
    }

    private static List<Release> recognizeVersions(
            final List<String> rawVersions,
            final ReleaseCatalog catalog,
            final List<String> unrecognized) {
        LinkedHashSet<Release> result = new LinkedHashSet<>();
        for (String rawVersion : rawVersions) {
            Release release = catalog.recognize(rawVersion);
            if (release == null) {
                unrecognized.add(rawVersion);
            } else {
                result.add(release);
            }
        }
        return result.stream()
                .sorted(Comparator.comparingInt(release -> release.sequence))
                .toList();
    }
}


final class ProportionState {
    final List<Double> observations = new ArrayList<>();
    int observedCount;
    int estimatedCount;
    int skippedObservationCount;
    int sameVersionCount;
    int coldStartCount;
    int notEstimableCount;

    int observationCount() {
        return observations.size();
    }

    double sum() {
        return observations.stream().mapToDouble(Double::doubleValue).sum();
    }

    double mean() {
        return observations.isEmpty() ? 1.0d : sum() / observations.size();
    }
}


final class ProportionEstimator {
    private static final Logger LOGGER = Logger.getLogger(ProportionEstimator.class.getName());
    private final ReleaseCatalog catalog;
    private final boolean traceEnabled;

    ProportionEstimator(final ReleaseCatalog catalog, final boolean traceEnabled) {
        this.catalog = catalog;
        this.traceEnabled = traceEnabled;
    }

    void estimate(final List<Ticket> tickets) {
        List<Ticket> ordered = orderedTickets(tickets);
        ProportionState state = new ProportionState();
        LOGGER.info(() -> "[PROPORTION][START] ticketOrdinati=" + ordered.size()
                + "; formulaOsservata=P=(FVindex-IVindex)/(FVindex-OVindex)"
                + "; formulaStima=IVindex=FVindex-(FVindex-OVindex)*mediaP");

        for (Ticket ticket : ordered) {
            ticket.priorProportionObservationCount = state.observationCount();
            if (ticket.injectedVersion != null) {
                processObserved(ticket, state);
            } else {
                estimateMissing(ticket, state);
            }
        }
        logSummary(state);
    }

    private static List<Ticket> orderedTickets(final List<Ticket> tickets) {
        List<Ticket> ordered = new ArrayList<>(tickets);
        ordered.sort(Comparator
                .comparing(Ticket::fixOrderingDate,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ticket -> ticket.issue.key));
        return ordered;
    }

    private void processObserved(final Ticket ticket, final ProportionState state) {
        Double observed = observedProportion(ticket);
        if (observed == null) {
            state.skippedObservationCount++;
            trace(Level.FINE, () -> "[PROPORTION][SKIPPED_OBSERVATION] ticket="
                    + ticket.issue.key
                    + "; IV=" + releaseName(ticket.injectedVersion)
                    + "; OV=" + releaseName(ticket.openingVersion)
                    + "; FV=" + releaseName(ticket.fixedVersion)
                    + "; motivo=" + observedProportionSkipReason(ticket)
                    + "; osservazioniDisponibili=" + state.observationCount());
            return;
        }

        int fvIndex = ticket.fixedVersion.sequence;
        int ivIndex = ticket.injectedVersion.sequence;
        int ovIndex = ticket.openingVersion.sequence;
        ticket.proportionUsed = observed;
        trace(Level.FINE, () -> String.format(
                Locale.ROOT,
                "[PROPORTION][OBSERVED] ticket=%s; IV=%s(index=%d); "
                        + "OV=%s(index=%d); FV=%s(index=%d); "
                        + "P=(%d-%d)/(%d-%d)=%d/%d=%.6f; osservazioniPrecedenti=%d",
                ticket.issue.key, ticket.injectedVersion.version, ivIndex,
                ticket.openingVersion.version, ovIndex,
                ticket.fixedVersion.version, fvIndex,
                fvIndex, ivIndex, fvIndex, ovIndex,
                fvIndex - ivIndex, fvIndex - ovIndex, observed,
                state.observationCount()));
        state.observations.add(observed);
        state.observedCount++;
    }

    private void estimateMissing(final Ticket ticket, final ProportionState state) {
        if (ticket.openingVersion == null || ticket.fixedVersion == null) {
            markNotEstimable(ticket, state, "MISSING_OV_OR_FV");
            return;
        }

        int fvIndex = ticket.fixedVersion.sequence;
        int ovIndex = ticket.openingVersion.sequence;
        if (fvIndex < ovIndex) {
            markNotEstimable(ticket, state, "FV_BEFORE_OV");
        } else if (fvIndex == ovIndex) {
            useOpeningVersion(ticket, state, "SAME_AS_OPENING_VERSION", false);
        } else if (state.observations.isEmpty()) {
            useOpeningVersion(ticket, state, "SIMPLE_COLD_START", true);
        } else {
            applyIncrementalEstimate(ticket, state, ovIndex, fvIndex);
        }
    }

    private void markNotEstimable(
            final Ticket ticket,
            final ProportionState state,
            final String reason) {
        ticket.injectedVersionSource = "NOT_ESTIMABLE";
        state.notEstimableCount++;
        LOGGER.warning(() -> "[PROPORTION][NOT_ESTIMABLE] ticket=" + ticket.issue.key
                + "; OV=" + releaseName(ticket.openingVersion)
                + "; FV=" + releaseName(ticket.fixedVersion)
                + "; motivo=" + reason
                + "; osservazioniDisponibili=" + state.observationCount());
    }

    private void useOpeningVersion(
            final Ticket ticket,
            final ProportionState state,
            final String source,
            final boolean coldStart) {
        ticket.injectedVersion = ticket.openingVersion;
        ticket.injectedVersionSource = source;
        ticket.proportionUsed = 1.0d;
        if (coldStart) state.coldStartCount++; else state.sameVersionCount++;
        String type = coldStart ? "COLD_START" : "SAME_VERSION";
        trace(Level.FINE, () -> "[PROPORTION][" + type + "] ticket=" + ticket.issue.key
                + "; OV=" + ticket.openingVersion.version
                + "(index=" + ticket.openingVersion.sequence + ")"
                + "; FV=" + ticket.fixedVersion.version
                + "(index=" + ticket.fixedVersion.sequence + ")"
                + "; IV=OV=" + ticket.openingVersion.version
                + "; PUsata=1.000000");
    }

    private void applyIncrementalEstimate(
            final Ticket ticket,
            final ProportionState state,
            final int ovIndex,
            final int fvIndex) {
        double sum = state.sum();
        double mean = state.mean();
        double raw = fvIndex - (fvIndex - ovIndex) * mean;
        int rounded = (int) Math.round(raw);
        int lowerBounded = Math.max(0, rounded);
        int predicted = Math.min(lowerBounded, ovIndex);

        ticket.injectedVersion = catalog.bySequence(predicted);
        ticket.injectedVersionSource = "PROPORTION_INCREMENTAL";
        ticket.proportionUsed = mean;
        state.estimatedCount++;
        trace(Level.FINE, () -> String.format(
                Locale.ROOT,
                "[PROPORTION][ESTIMATED] ticket=%s; osservazioni=%d; "
                        + "sommaP=%.6f; mediaP=%.6f; OV=%s(index=%d); FV=%s(index=%d); "
                        + "IVindexRaw=%d-(%d-%d)*%.6f=%.6f; IVindexRound=%d; "
                        + "IVindexDopoMinimoZero=%d; IVindexFinal=%d; IV=%s",
                ticket.issue.key, state.observationCount(), sum, mean,
                ticket.openingVersion.version, ovIndex,
                ticket.fixedVersion.version, fvIndex,
                fvIndex, fvIndex, ovIndex, mean, raw,
                rounded, lowerBounded, predicted, releaseName(ticket.injectedVersion)));
    }

    private static Double observedProportion(final Ticket ticket) {
        if (ticket.injectedVersion == null
                || ticket.openingVersion == null
                || ticket.fixedVersion == null) return null;
        int denominator = ticket.fixedVersion.sequence - ticket.openingVersion.sequence;
        if (denominator <= 0 || ticket.injectedVersion.sequence > ticket.openingVersion.sequence) {
            return null;
        }
        return (double) (ticket.fixedVersion.sequence - ticket.injectedVersion.sequence)
                / denominator;
    }

    private static String observedProportionSkipReason(final Ticket ticket) {
        if (ticket.injectedVersion == null) return "MISSING_IV";
        if (ticket.openingVersion == null) return "MISSING_OV";
        if (ticket.fixedVersion == null) return "MISSING_FV";
        int denominator = ticket.fixedVersion.sequence - ticket.openingVersion.sequence;
        if (denominator <= 0) return "NON_POSITIVE_DENOMINATOR:" + denominator;
        return ticket.injectedVersion.sequence > ticket.openingVersion.sequence
                ? "IV_AFTER_OV" : "UNKNOWN";
    }

    private static String releaseName(final Release release) {
        return release == null ? "" : release.version;
    }

    private void logSummary(final ProportionState state) {
        LOGGER.info(() -> "[PROPORTION][END] osservazioniDiretteUtilizzate="
                + state.observationCount()
                + "; observed=" + state.observedCount
                + "; estimated=" + state.estimatedCount
                + "; skippedObservation=" + state.skippedObservationCount
                + "; sameVersion=" + state.sameVersionCount
                + "; coldStart=" + state.coldStartCount
                + "; notEstimable=" + state.notEstimableCount);
    }

    private void trace(final Level defaultLevel, final java.util.function.Supplier<String> message) {
        LOGGER.log(traceEnabled ? Level.INFO : defaultLevel, message);
    }
}


final class LifecycleValidator {
    void validate(final Ticket ticket) {
        ticket.violations.clear();
        validateDates(ticket);
        validateVersionOrder(ticket);
        validateAffectedVersions(ticket);
        validateCommitEvidence(ticket);
        assignStatus(ticket);
    }

    private static void validateDates(final Ticket ticket) {
        LocalDate createDate = DatasetText.dateOf(ticket.issue.createDate);
        LocalDate closedDate = DatasetText.dateOf(ticket.issue.closedDate);
        if (ticket.issue.createDate == null) {
            ticket.addGap("MISSING_CREATE_DATE");
        }
        if (ticket.issue.createDate != null && ticket.issue.closedDate != null
                && ticket.issue.createDate.isAfter(ticket.issue.closedDate)) {
            ticket.addViolation("CREATE_DATE_AFTER_CLOSED_DATE");
        }
        if (ticket.openingVersion == null) {
            ticket.addGap("MISSING_OPENING_VERSION");
        } else if (createDate != null && ticket.openingVersion.releaseDate.isAfter(createDate)) {
            ticket.addViolation("OPENING_VERSION_AFTER_TICKET_CREATION");
        }
        if (closedDate != null && ticket.fixedVersion != null
                && closedDate.isAfter(ticket.fixedVersion.releaseDate)) {
            ticket.addWarning("TICKET_CLOSED_AFTER_FIXED_VERSION_RELEASE");
        }
    }

    private static void validateVersionOrder(final Ticket ticket) {
        if (ticket.fixedVersion == null) ticket.addGap("MISSING_FIXED_VERSION");
        if (ticket.injectedVersion == null) ticket.addGap("MISSING_INJECTED_VERSION");

        if (ticket.injectedVersion != null && ticket.openingVersion != null
                && ticket.injectedVersion.sequence > ticket.openingVersion.sequence) {
            ticket.addViolation("INJECTED_VERSION_AFTER_OPENING_VERSION");
        }
        if (ticket.openingVersion != null && ticket.fixedVersion != null
                && ticket.openingVersion.sequence > ticket.fixedVersion.sequence) {
            ticket.addViolation("OPENING_VERSION_AFTER_FIXED_VERSION");
        }
        if (ticket.injectedVersion != null && ticket.fixedVersion != null
                && ticket.injectedVersion.sequence > ticket.fixedVersion.sequence) {
            ticket.addViolation("INJECTED_VERSION_AFTER_FIXED_VERSION");
        }
    }

    private static void validateAffectedVersions(final Ticket ticket) {
        for (Release affected : ticket.affectedVersions) {
            if (ticket.injectedVersion != null
                    && affected.sequence < ticket.injectedVersion.sequence) {
                ticket.addViolation("AFFECTED_VERSION_BEFORE_INJECTED_VERSION:"
                        + affected.version);
            }
            if (ticket.fixedVersion != null && affected.sequence > ticket.fixedVersion.sequence) {
                ticket.addViolation("AFFECTED_VERSION_AFTER_FIXED_VERSION:"
                        + affected.version);
            }
        }
    }

    private static void validateCommitEvidence(final Ticket ticket) {
        if (ticket.commitCandidates.isEmpty()) {
            ticket.addGap("NO_FIX_COMMIT_FOUND");
        } else if (ticket.validCommits.isEmpty()) {
            ticket.addViolation("NO_TEMPORALLY_VALID_FIX_COMMIT");
        } else if (ticket.validCommits.stream()
                .flatMap(commit -> commit.productionJavaPaths().stream())
                .findAny().isEmpty()) {
            ticket.addGap("NO_PRODUCTION_JAVA_FILES_IN_VALID_COMMITS");
        }
    }

    private static void assignStatus(final Ticket ticket) {
        if (!ticket.violations.isEmpty()) {
            ticket.consistencyStatus = ConsistencyStatus.INCONSISTENT;
        } else if (!ticket.dataGaps.isEmpty()) {
            ticket.consistencyStatus = ConsistencyStatus.NOT_FULLY_CHECKABLE;
        } else {
            ticket.consistencyStatus = ConsistencyStatus.CONSISTENT;
        }
    }
}

