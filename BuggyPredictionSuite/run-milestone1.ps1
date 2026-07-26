param(
    [ValidateSet("release", "tickets", "commits", "lifecycle", "metrics", "labeling", "workbook")]
    [string]$Step = "workbook",
    [int]$MaxReleases = 0,
    [switch]$NoGitHubRefresh,
    [switch]$NoProportionTrace
)

$arguments = @(
    "-pl", "milestone-1",
    "clean", "compile", "exec:java",
    "-Dmilestone.step=$Step"
)

if ($MaxReleases -gt 0) {
    $arguments += "-Dmilestone.maxReleases=$MaxReleases"
}
if ($NoGitHubRefresh) {
    $arguments += "-Dmilestone.githubRefresh=false"
}
if ($NoProportionTrace) {
    $arguments += "-Dmilestone.proportionTrace=false"
}

mvn @arguments
exit $LASTEXITCODE
