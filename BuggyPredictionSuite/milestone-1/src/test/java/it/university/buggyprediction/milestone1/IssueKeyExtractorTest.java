package it.university.buggyprediction.milestone1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IssueKeyExtractorTest {
    @Test
    void extractsIssuesButNotReleaseNames() {
        IssueKeyExtractor extractor = new IssueKeyExtractor();
        assertEquals(Set.of("SYNCOPE-836"), extractor.extract("Fix SYNCOPE-836"));
        assertTrue(extractor.extract("Release syncope-1.0.0").isEmpty());
    }
}
