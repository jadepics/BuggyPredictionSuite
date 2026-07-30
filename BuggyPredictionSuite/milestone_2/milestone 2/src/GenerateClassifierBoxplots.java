import java.nio.file.Path;

/**
 * Genera un pannello 2x2 per ciascun classificatore (NaiveBayes, IBk, RandomForest),
 * confrontando le quattro configurazioni PURE, FS, OVER e FS_OVER.
 */
public final class GenerateClassifierBoxplots {
    private GenerateClassifierBoxplots() {
    }

    public static void main(String[] args) {
        try {
            Path input = WekaBoxplotSupport.resolveInput(args);
            Path output = WekaBoxplotSupport.resolveOutput(args, input);
            WekaBoxplotSupport.Dataset dataset = WekaBoxplotSupport.load(input);

            WekaBoxplotSupport.generateClassifierPanels(dataset, output);

            System.out.println("Boxplot per classificatore generati correttamente.");
            System.out.println("Input:  " + input);
            System.out.println("Output: " + output);
            System.out.println("Righe lette: " + dataset.rows);
        } catch (Exception e) {
            System.err.println("Errore durante la generazione dei boxplot per classificatore:");
            System.err.println(e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
