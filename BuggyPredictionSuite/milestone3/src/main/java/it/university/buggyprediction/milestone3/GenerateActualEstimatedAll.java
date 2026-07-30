package it.university.buggyprediction.milestone3;

public final class GenerateActualEstimatedAll {

    private GenerateActualEstimatedAll() {
    }

    public static void main(String[] args) {
        WekaActualEstimatedAnalysis.runSafely(
                WekaActualEstimatedAnalysis::generateAll
        );
    }
}
