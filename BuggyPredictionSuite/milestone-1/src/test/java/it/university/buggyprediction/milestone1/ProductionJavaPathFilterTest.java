package it.university.buggyprediction.milestone1;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ProductionJavaPathFilterTest {
    @Test
    void acceptsProductionAndRejectsTests() {
        assertTrue(ProductionJavaPathFilter.isProductionJavaPath(
                "core/src/main/java/org/apache/Sample.java"));
        assertFalse(ProductionJavaPathFilter.isProductionJavaPath(
                "core/src/test/java/org/apache/SampleTest.java"));
        assertFalse(ProductionJavaPathFilter.isProductionJavaPath(
                "core/target/generated-sources/Sample.java"));
    }
}
