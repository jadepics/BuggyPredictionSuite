package it.university.buggyprediction.milestone1;


import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

final class WorkbookStep implements MilestoneStep {
    private static final Logger LOGGER = Logger.getLogger(WorkbookStep.class.getName());
    private final WorkbookWriter writer;
    private final GitHubService gitHubService;

    WorkbookStep(final WorkbookWriter writer, final GitHubService gitHubService) {
        this.writer = writer;
        this.gitHubService = gitHubService;
    }

    @Override
    public String id() { return "workbook"; }

    @Override
    public String description() { return "Generazione del workbook Excel unico"; }

    @Override
    public void execute(final PipelineContext context) throws Exception {
        context.completedAt(Instant.now());
        context.repositoryHead(gitHubService.currentHead());
        WorkbookData data = new WorkbookData(
                context.requireReleaseCatalog(),
                context.requireSelectedReleases(),
                context.requireTickets(),
                context.requireDatasetRows(),
                context.excludedTickets(),
                context.config().releaseFraction(),
                context.startedAt(),
                context.completedAt(),
                context.repositoryHead());
        writer.write(context.config().outputWorkbook(), data);
        LOGGER.log(Level.INFO, "File generato: {0}", context.config().outputWorkbook());
    }
}
