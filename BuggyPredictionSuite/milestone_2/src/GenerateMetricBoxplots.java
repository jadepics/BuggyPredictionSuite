import java.nio.file.Path;

/**
 * Genera un boxplot separato per ciascuna metrica e il pannello 2x2
 * Precision/Recall/AUC/Kappa, usando i 100 valori fold-level di ogni configurazione.
 */
public final class GenerateMetricBoxplots {
    private GenerateMetricBoxplots() {
    }

    public static void main(String[] args) {
        try {
            Path input = WekaBoxplotSupport.resolveInput(args);
            Path output = WekaBoxplotSupport.resolveOutput(args, input);
            WekaBoxplotSupport.Dataset dataset = WekaBoxplotSupport.load(input);

            WekaBoxplotSupport.generateIndividualMetricPlots(dataset, output);
            WekaBoxplotSupport.generateCorePanel(dataset, output);
            WekaBoxplotSupport.writeStatistics(dataset, output);

            System.out.println("Boxplot delle metriche generati correttamente.");
            System.out.println("Input:  " + input);
            System.out.println("Output: " + output);
            System.out.println("Righe lette: " + dataset.rows);
        } catch (Exception e) {
            System.err.println("Errore durante la generazione dei boxplot delle metriche:");
            System.err.println(e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
