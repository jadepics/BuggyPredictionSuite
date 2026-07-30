package it.university.buggyprediction.milestone3;

public final class GenerateActualEstimatedA {

    private GenerateActualEstimatedA() {
    }

    public static void main(String[] args) {
        WekaActualEstimatedAnalysis.runSafely(
                () -> WekaActualEstimatedAnalysis.generateSingle(
                        WekaActualEstimatedAnalysis.DatasetId.A
                )
        );
    }
}
