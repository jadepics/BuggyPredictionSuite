package it.university.buggyprediction.milestone3;

public final class GenerateActualEstimatedBPlus {

    private GenerateActualEstimatedBPlus() {
    }

    public static void main(String[] args) {
        WekaActualEstimatedAnalysis.runSafely(
                () -> WekaActualEstimatedAnalysis.generateSingle(
                        WekaActualEstimatedAnalysis.DatasetId.B_PLUS
                )
        );
    }
}
