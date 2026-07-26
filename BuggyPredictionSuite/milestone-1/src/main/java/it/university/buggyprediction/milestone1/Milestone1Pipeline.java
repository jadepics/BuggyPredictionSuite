package it.university.buggyprediction.milestone1;


import java.nio.file.Files;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

final class Milestone1Pipeline {
    private static final Logger LOGGER = Logger.getLogger(Milestone1Pipeline.class.getName());

    private final Milestone1Config config;
    private final GitHubService gitHubService;
    private final List<MilestoneStep> steps;

    Milestone1Pipeline(final Milestone1Config config) {
        this.config = config;
        JiraClient jiraClient = new JiraClient();
        gitHubService = new GitHubService(config);
        steps = List.of(
                new ReleaseStep(jiraClient, gitHubService),
                new JiraTicketStep(jiraClient),
                new CommitStep(gitHubService),
                new TicketLifecycleStep(),
                new SourceMetricsStep(gitHubService),
                new LabelingStep(),
                new WorkbookStep(new WorkbookWriter(), gitHubService));
    }

    void execute() throws Exception {
        Files.createDirectories(config.outputWorkbook().getParent());
        logStart();
        gitHubService.prepare();
        LOGGER.log(Level.INFO, "Mirror GitHub Apache Syncope: {0}",
                gitHubService.mirrorPath());

        PipelineContext context = new PipelineContext(config);
        int targetIndex = indexOf(config.targetStep());
        for (int index = 0; index <= targetIndex; index++) {
            MilestoneStep step = steps.get(index);
            LOGGER.log(Level.INFO, "[{0}/{1}] {2}",
                    new Object[]{index + 1, steps.size(), step.description()});
            step.execute(context);
        }

        if (targetIndex == steps.size() - 1) {
            LOGGER.info("Milestone 1 completata.");
        } else {
            LOGGER.log(Level.INFO,
                    "Esecuzione parziale completata fino alla fase: {0}",
                    config.targetStep());
        }
    }

    private int indexOf(final String stepId) {
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index).id().equals(stepId)) return index;
        }
        throw new IllegalArgumentException("Fase non registrata: " + stepId);
    }

    private void logStart() {
        LOGGER.info("=== Buggy Prediction Suite - Milestone 1 ===");
        LOGGER.log(Level.INFO, "Pipeline revision: {0}", Milestone1Constants.PIPELINE_REVISION);
        LOGGER.log(Level.INFO, "Modulo: {0}", config.milestoneRoot());
        LOGGER.log(Level.INFO, "Output: {0}", config.outputWorkbook());
        LOGGER.log(Level.INFO, "Fase finale richiesta: {0}", config.targetStep());
    }
}
//inserisco codesmell