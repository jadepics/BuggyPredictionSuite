import java.nio.file.Path;

/**
 * Main consigliato: genera tutti i grafici in una sola esecuzione.
 */
public final class GenerateAllBoxplots {
    private GenerateAllBoxplots() {
    }

    public static void main(String[] args) {
        try {
            Path input = WekaBoxplotSupport.resolveInput(args);
            Path output = WekaBoxplotSupport.resolveOutput(args, input);
            WekaBoxplotSupport.Dataset dataset = WekaBoxplotSupport.load(input);

            WekaBoxplotSupport.generateIndividualMetricPlots(dataset, output);
            WekaBoxplotSupport.generateCorePanel(dataset, output);
            WekaBoxplotSupport.generateClassifierPanels(dataset, output);
            WekaBoxplotSupport.writeStatistics(dataset, output);

            System.out.println("Generazione completata correttamente.");
            System.out.println("Input:  " + input);
            System.out.println("Output: " + output);
            System.out.println("Righe lette: " + dataset.rows);
            System.out.println("Configurazioni: " + dataset.configurations().size());
        } catch (Exception e) {
            System.err.println("Errore durante la generazione dei boxplot:");
            System.err.println(e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
