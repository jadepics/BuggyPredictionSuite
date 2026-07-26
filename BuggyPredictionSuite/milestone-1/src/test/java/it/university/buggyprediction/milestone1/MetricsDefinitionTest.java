package it.university.buggyprediction.milestone1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MetricsDefinitionTest {

    @Test
    void copiesPreviousReleasePmdCountsIntoDatasetFeatures() {
        ClassMetrics row = new ClassMetrics();
        row.detectedPmdSmells = PmdSmellSnapshot.success(Map.of(
                "TooManyFields", 1,
                "NcssCount", 2));

        row.applyPreviousSmells(
                "1.0.0",
                PmdSmellSnapshot.success(Map.of(
                        "GodClass", 1,
                        "CyclomaticComplexity", 3)));

        assertEquals("1.0.0", row.smellSourceRelease);
        assertEquals(4, row.nSmells);
        assertEquals(2, row.nPmdRuleTypes);
        assertEquals(
                "CyclomaticComplexity(3) | GodClass(1)",
                row.pmdRules);
        assertEquals(PmdSmellSnapshot.STATUS_OK, row.pmdAnalysisStatus);
        assertEquals("", row.pmdAnalysisWarning);

        PmdSmellSnapshot current = row.currentSmells();
        assertEquals(3, current.violationCount());
        assertEquals(2, current.ruleTypeCount());
        assertTrue(current.rules().contains("NcssCount(2)"));
        assertTrue(current.rules().contains("TooManyFields(1)"));
    }

    @Test
    void newFileHasNoPreviousPmdSource() {
        ClassMetrics row = new ClassMetrics();

        row.applyPreviousSmells("1.0.0", null);

        assertEquals("", row.smellSourceRelease);
        assertEquals(0, row.nSmells);
        assertEquals(0, row.nPmdRuleTypes);
        assertEquals(PmdSmellSnapshot.STATUS_NO_PREVIOUS_SOURCE,
                row.pmdAnalysisStatus);
    }

    @Test
    void pmdErrorIsNotRepresentedAsZeroViolations() {
        ClassMetrics row = new ClassMetrics();

        row.applyPreviousSmells(
                "1.0.0",
                PmdSmellSnapshot.error("Parse failure"));

        assertEquals("1.0.0", row.smellSourceRelease);
        assertNull(row.nSmells);
        assertNull(row.nPmdRuleTypes);
        assertEquals(PmdSmellSnapshot.STATUS_ERROR, row.pmdAnalysisStatus);
        assertEquals("Parse failure", row.pmdAnalysisWarning);
    }

    @Test
    void copiesProcessMetricsWithoutChangingProductMetrics() {
        FileProcessMetrics process = new FileProcessMetrics();
        process.locTouched = 120;
        process.revisions = 8;
        process.defectFixes = 2;
        process.authors = 3;
        process.locAdded = 75;
        process.maxLocAdded = 30;
        process.averageLocAdded = 9.375d;
        process.maxChurn = 45;
        process.averageChurn = 15.0d;
        process.changeSetSize = 40;
        process.maxChangeSet = 12;
        process.averageChangeSet = 5.0d;
        process.age = 900.0d;
        process.weightedAge = 250.0d;
        process.averageChangeInterval = 45.0d;

        ClassMetrics row = new ClassMetrics();
        row.loc = 300;
        row.cloc = 50;
        row.wmc = 40;
        row.npm = 20;
        row.applyProcessMetrics(process);

        assertEquals(300, row.loc);
        assertEquals(120, row.locTouched);
        assertEquals(8, row.revisions);
        assertEquals(2, row.defectFixes);
        assertEquals(75, row.locAdded);
        assertEquals(45, row.maxChurn);
        assertEquals(15.0d, row.averageChurn);
    }
}
