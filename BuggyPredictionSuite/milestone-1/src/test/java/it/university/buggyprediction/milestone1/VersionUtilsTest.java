package it.university.buggyprediction.milestone1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class VersionUtilsTest {
    @Test
    void canonicalizesSyncopeTags() {
        assertEquals("1.0.0", VersionUtils.canonicalVersion("syncope-1.0.0"));
        assertEquals("2.0.0-m5", VersionUtils.canonicalVersion("2.0.0.M5"));
    }

    @Test
    void ordersMilestonesBeforeFinalRelease() {
        int result = VersionUtils.compareVersionNames("2.0.0-M5", "2.0.0");
        org.junit.jupiter.api.Assertions.assertTrue(result < 0);
    }
}
