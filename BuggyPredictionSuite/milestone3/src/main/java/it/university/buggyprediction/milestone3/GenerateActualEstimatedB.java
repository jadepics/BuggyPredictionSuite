package it.university.buggyprediction.milestone3;

public final class GenerateActualEstimatedB {

    private GenerateActualEstimatedB() {
    }

    public static void main(String[] args) {
        WekaActualEstimatedAnalysis.runSafely(
                () -> WekaActualEstimatedAnalysis.generateSingle(
                        WekaActualEstimatedAnalysis.DatasetId.B
                )
        );
    }
}
