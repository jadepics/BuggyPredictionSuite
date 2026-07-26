package it.university.buggyprediction.milestone1;


interface MilestoneStep {
    String id();

    String description();

    void execute(PipelineContext context) throws Exception;
}
