package it.university.buggyprediction.milestone1;


import java.util.logging.Level;
import java.util.logging.Logger;

public final class BuildMilestone1Dataset {
    private static final Logger LOGGER =
            Logger.getLogger(BuildMilestone1Dataset.class.getName());

    private BuildMilestone1Dataset() {
    }

    public static void main(final String[] args) {
        try {
            Milestone1Config config = Milestone1Config.fromSystemProperties();
            new Milestone1Pipeline(config).execute();
        } catch (Exception exception) {
            LOGGER.log(Level.SEVERE, "Costruzione della Milestone 1 fallita", exception);
            throw new IllegalStateException("Impossibile completare la Milestone 1", exception);
        }
    }
}
