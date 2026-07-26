package it.university.buggyprediction.milestone1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PmdSmellAnalyzerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void countsTotalViolationsAndDistinctRuleTypes() throws Exception {
        Path source = temporaryDirectory
                .resolve("src/main/java/example/Example.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, createSmellySource(), StandardCharsets.UTF_8);

        Map<String, PmdSmellSnapshot> result = new PmdSmellAnalyzer().analyze(
                temporaryDirectory,
                List.of(source));

        PmdSmellSnapshot snapshot = result.get(
                "src/main/java/example/Example.java");
        assertNotNull(snapshot);
        assertEquals(PmdSmellSnapshot.STATUS_OK, snapshot.status());
        assertTrue(snapshot.violationCount() >= 2);
        assertTrue(snapshot.ruleTypeCount() >= 2);
        assertTrue(snapshot.rules().contains("ExcessiveParameterList"));
        assertTrue(snapshot.rules().contains("TooManyFields"));
    }

    private static String createSmellySource() {
        return """
                package example;

                final class Example {
                    int f01; int f02; int f03; int f04;
                    int f05; int f06; int f07; int f08;
                    int f09; int f10; int f11; int f12;
                    int f13; int f14; int f15; int f16;

                    void operation(int a, int b, int c, int d, int e) {
                        f01 = a + b + c + d + e;
                    }
                }
                """;
    }
}
