package it.university.buggyprediction.milestone1;


import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;

record Milestone1Config(
        Path milestoneRoot,
        Path outputWorkbook,
        double releaseFraction,
        Integer maxSelectedReleases,
        boolean githubRefresh,
        boolean proportionTrace,
        String targetStep) {

    private static final Set<String> VALID_STEPS = Set.of(
            "release", "tickets", "commits", "lifecycle",
            "metrics", "labeling", "workbook");

    static Milestone1Config fromSystemProperties() {
        Path root = findMilestoneRoot();
        return new Milestone1Config(
                root,
                root.resolve("output").resolve("milestone_1_dataset.xlsx"),
                readFraction(),
                readPositiveInteger("milestone.maxReleases"),
                Boolean.parseBoolean(System.getProperty("milestone.githubRefresh", "true")),
                Boolean.parseBoolean(System.getProperty("milestone.proportionTrace", "true")),
                readTargetStep());
    }

    private static String readTargetStep() {
        String raw = System.getProperty(
                "milestone.step",
                System.getProperty("milestone.until", "workbook"));
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (!VALID_STEPS.contains(value)) {
            throw new IllegalArgumentException(
                    "Fase non valida: " + raw + ". Valori ammessi: " + VALID_STEPS);
        }
        return value;
    }

    private static double readFraction() {
        String raw = System.getProperty(
                "milestone.releaseFraction",
                Double.toString(Milestone1Constants.DEFAULT_RELEASE_FRACTION)).trim();
        try {
            double value = Double.parseDouble(raw);
            if (value <= 0.0d || value > 1.0d) {
                throw new IllegalArgumentException(
                        "milestone.releaseFraction deve essere nell'intervallo (0, 1].");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Valore non valido per milestone.releaseFraction: " + raw,
                    exception);
        }
    }

    private static Integer readPositiveInteger(final String propertyName) {
        String raw = System.getProperty(propertyName);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value <= 0) {
                throw new IllegalArgumentException(propertyName + " deve essere positivo.");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Valore non valido per " + propertyName + ": " + raw,
                    exception);
        }
    }

    private static Path findMilestoneRoot() {
        String configured = System.getProperty("milestone.root");
        if (configured != null && !configured.isBlank()) {
            Path root = Paths.get(configured).toAbsolutePath().normalize();
            assertModule(root);
            return root;
        }

        Path current = Paths.get("").toAbsolutePath().normalize();
        while (current != null) {
            for (Path candidate : new Path[]{current, current.resolve("milestone-1")}) {
                if (isModule(candidate)) {
                    return candidate.toAbsolutePath().normalize();
                }
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
                "Modulo milestone-1 non trovato. Eseguire Maven dalla root del progetto "
                        + "o specificare -Dmilestone.root=<percorso>.");
    }

    private static boolean isModule(final Path candidate) {
        return candidate != null
                && Files.isRegularFile(candidate.resolve("pom.xml"))
                && Files.isDirectory(candidate.resolve("src/main/java"));
    }

    private static void assertModule(final Path candidate) {
        if (!isModule(candidate)) {
            throw new IllegalArgumentException(
                    "La directory indicata non contiene il modulo milestone-1: " + candidate);
        }
    }
}
