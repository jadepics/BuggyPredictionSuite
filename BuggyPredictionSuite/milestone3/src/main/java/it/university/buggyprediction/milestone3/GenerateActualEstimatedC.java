package it.university.buggyprediction.milestone3;

public final class GenerateActualEstimatedC {

    private GenerateActualEstimatedC() {
    }

    public static void main(String[] args) {
        WekaActualEstimatedAnalysis.runSafely(
                () -> WekaActualEstimatedAnalysis.generateSingle(
                        WekaActualEstimatedAnalysis.DatasetId.C
                )
        );
    }
}
