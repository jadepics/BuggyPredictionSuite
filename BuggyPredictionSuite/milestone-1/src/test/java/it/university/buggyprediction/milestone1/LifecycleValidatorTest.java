package it.university.buggyprediction.milestone1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class LifecycleValidatorTest {
    @Test
    void acceptsAffectedVersionAfterOpeningWhenItIsWithinIvAndFv() {
        Release iv = release("1.0.0", 0, LocalDate.of(2020, 1, 1));
        Release affectedAfterOpening = release("1.0.1", 1, LocalDate.of(2020, 2, 1));
        Release fv = release("1.0.2", 2, LocalDate.of(2020, 4, 1));
        ReleaseCatalog catalog = catalog(iv, affectedAfterOpening, fv);
        IssueRaw issue = new IssueRaw(
                "1", "SYNCOPE-1", "summary", "Closed", "Fixed", "Major",
                LocalDate.of(2020, 1, 15).atStartOfDay().atOffset(ZoneOffset.UTC),
                LocalDate.of(2020, 3, 20).atStartOfDay().atOffset(ZoneOffset.UTC),
                List.of(), List.of());
        Ticket ticket = new Ticket(issue, catalog);
        ticket.injectedVersion = iv;
        ticket.openingVersion = iv;
        ticket.fixedVersion = fv;
        ticket.affectedVersions.add(affectedAfterOpening);

        GitCommit commit = new GitCommit(
                "hash", null,
                LocalDate.of(2020, 3, 1).atStartOfDay().atOffset(ZoneOffset.UTC),
                "SYNCOPE-1 fix");
        commit.fileChanges.add(new FileChange(
                "M", null, "core/src/main/java/org/apache/Sample.java"));
        commit.evaluateFor(ticket);
        ticket.commitCandidates.add(commit);
        ticket.validCommits.add(commit);

        new LifecycleValidator().validate(ticket);

        assertTrue(ticket.violations.isEmpty());
        assertEquals(ConsistencyStatus.CONSISTENT, ticket.consistencyStatus);
    }

    private static Release release(final String version, final int sequence, final LocalDate date) {
        Release release = new Release(
                version, version, version, date, date, "JIRA", "", "",
                "syncope-" + version, "hash-" + version, date, false);
        release.sequence = sequence;
        return release;
    }

    private static ReleaseCatalog catalog(final Release... releases) {
        LinkedHashMap<String, Release> index = new LinkedHashMap<>();
        for (Release release : releases) index.put(release.canonicalVersion, release);
        return new ReleaseCatalog(List.of(releases), List.of(releases), index, List.of());
    }
}
