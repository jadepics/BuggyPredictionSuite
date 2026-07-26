package it.university.buggyprediction.milestone1;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProportionEstimatorTest {
    @Test
    void estimatesInjectedVersionFromPreviousObservedTickets() {
        Release r0 = release("1.0.0", 0, LocalDate.of(2020, 1, 1));
        Release r1 = release("1.0.1", 1, LocalDate.of(2020, 2, 1));
        Release r2 = release("1.0.2", 2, LocalDate.of(2020, 3, 1));
        Release r3 = release("1.0.3", 3, LocalDate.of(2020, 4, 1));
        ReleaseCatalog catalog = catalog(r0, r1, r2, r3);

        Ticket observed = ticket("SYNCOPE-1", catalog, LocalDate.of(2020, 3, 10));
        observed.injectedVersion = r0;
        observed.openingVersion = r1;
        observed.fixedVersion = r3;

        Ticket estimated = ticket("SYNCOPE-2", catalog, LocalDate.of(2020, 3, 20));
        estimated.openingVersion = r1;
        estimated.fixedVersion = r3;

        new ProportionEstimator(catalog, false).estimate(List.of(observed, estimated));

        assertEquals(1.5d, observed.proportionUsed);
        assertEquals(r0, estimated.injectedVersion);
        assertEquals(1.5d, estimated.proportionUsed);
        assertEquals(1, estimated.priorProportionObservationCount);
    }

    private static Ticket ticket(
            final String key,
            final ReleaseCatalog catalog,
            final LocalDate closedDate) {
        IssueRaw issue = new IssueRaw(
                key, key, "summary", "Closed", "Fixed", "Major",
                closedDate.minusDays(10).atStartOfDay().atOffset(ZoneOffset.UTC),
                closedDate.atStartOfDay().atOffset(ZoneOffset.UTC),
                List.of(), List.of());
        return new Ticket(issue, catalog);
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
