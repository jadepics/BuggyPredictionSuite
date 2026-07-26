package it.university.buggyprediction.milestone1;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class BugLabelerTest {
    @Test
    void labelsReleasesFromIvInclusiveToFvExclusive() {
        Release r0 = release("1.0.0", 0);
        Release r1 = release("1.0.1", 1);
        Release r2 = release("1.0.2", 2);
        ClassMetrics row0 = row(r0);
        ClassMetrics row1 = row(r1);
        ClassMetrics row2 = row(r2);

        IssueRaw issue = new IssueRaw(
                "1", "SYNCOPE-1", "summary", "Closed", "Fixed", "Major",
                LocalDate.of(2020, 1, 2).atStartOfDay().atOffset(ZoneOffset.UTC),
                LocalDate.of(2020, 2, 1).atStartOfDay().atOffset(ZoneOffset.UTC),
                List.of(), List.of());
        Ticket ticket = new Ticket(issue, null);
        ticket.injectedVersion = r0;
        ticket.openingVersion = r0;
        ticket.fixedVersion = r2;
        ticket.consistencyStatus = ConsistencyStatus.CONSISTENT;
        GitCommit commit = new GitCommit("hash", null,
                LocalDate.of(2020, 1, 20).atStartOfDay().atOffset(ZoneOffset.UTC),
                "SYNCOPE-1 fix");
        commit.fileChanges.add(new FileChange("M", null, row0.classPath));
        commit.temporalValid = true;
        ticket.validCommits.add(commit);
        ticket.commitCandidates.add(commit);

        new BugLabeler(List.of(r0, r1, r2), List.of(row0, row1, row2))
                .apply(List.of(ticket));

        assertTrue(row0.buggy);
        assertTrue(row1.buggy);
        assertFalse(row2.buggy);
    }

    private static Release release(final String version, final int sequence) {
        LocalDate date = LocalDate.of(2020, 1, 1).plusMonths(sequence);
        Release release = new Release(
                version, version, version, date, date, "JIRA", "", "",
                "syncope-" + version, "hash-" + version, date, false);
        release.sequence = sequence;
        return release;
    }

    private static ClassMetrics row(final Release release) {
        ClassMetrics row = new ClassMetrics();
        row.release = release;
        row.classPath = "core/src/main/java/org/apache/Sample.java";
        return row;
    }
}
