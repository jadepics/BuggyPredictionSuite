package it.university.buggyprediction.milestone1;


import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

final class CommitStep implements MilestoneStep {
    private static final Logger LOGGER = Logger.getLogger(CommitStep.class.getName());
    private final GitHubService gitHubService;

    CommitStep(final GitHubService gitHubService) {
        this.gitHubService = gitHubService;
    }

    @Override
    public String id() { return "commits"; }

    @Override
    public String description() { return "Ricerca fix commit e file modificati"; }

    @Override
    public void execute(final PipelineContext context) throws Exception {
        // Garantisce che la fase precedente sia stata eseguita, pur lasciando a
        // GitHubService la scansione completa e indipendente della cronologia.
        context.requireRawIssues();
        Map<String, List<GitCommit>> commits = gitHubService.collectIssueCommits();
        context.commitsByIssue(commits);
        LOGGER.log(Level.INFO, "Ticket con almeno un commit candidato: {0}", commits.size());
    }
}
//commit code smell