package it.university.buggyprediction.milestone1;


import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

final class JiraTicketStep implements MilestoneStep {
    private static final Logger LOGGER = Logger.getLogger(JiraTicketStep.class.getName());
    private final JiraClient jiraClient;

    JiraTicketStep(final JiraClient jiraClient) {
        this.jiraClient = jiraClient;
    }

    @Override
    public String id() { return "tickets"; }

    @Override
    public String description() { return "Recupero ticket Bug da JIRA"; }

    @Override
    public void execute(final PipelineContext context) throws Exception {
        List<IssueRaw> issues = jiraClient.fetchBugIssues();
        context.rawIssues(issues);
        LOGGER.log(Level.INFO, "Ticket Bug recuperati: {0}", issues.size());
    }
}
