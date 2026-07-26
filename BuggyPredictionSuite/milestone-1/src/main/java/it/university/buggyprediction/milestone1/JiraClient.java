package it.university.buggyprediction.milestone1;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import static it.university.buggyprediction.milestone1.Milestone1Constants.*;

final class JiraClient {
    private static final Logger LOGGER = Logger.getLogger(JiraClient.class.getName());
    private static final List<DateTimeFormatter> JIRA_DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ"));

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    List<JiraVersion> fetchVersions() throws IOException, InterruptedException {
        JsonNode root = fetchJson(URI.create(JIRA_VERSIONS_ENDPOINT));
        JsonNode values = root.isArray() ? root : root.path("values");
        if (!values.isArray()) {
            throw new IllegalStateException(
                    "Risposta JIRA versioni non riconosciuta: " + root);
        }

        List<JiraVersion> versions = new ArrayList<>();
        for (JsonNode node : values) {
            String name = node.path("name").asText("").trim();
            if (name.isBlank()) {
                continue;
            }

            String releaseDateText = node.path("releaseDate").asText("").trim();
            LocalDate releaseDate = null;
            if (!releaseDateText.isBlank()) {
                try {
                    releaseDate = LocalDate.parse(releaseDateText);
                } catch (DateTimeParseException ignored) {
                    // La versione rimane disponibile ma senza data utilizzabile.
                }
            }

            versions.add(new JiraVersion(
                    node.path("id").asText(""),
                    name,
                    VersionUtils.canonicalVersion(name),
                    node.path("released").asBoolean(false),
                    node.path("archived").asBoolean(false),
                    releaseDate,
                    node.path("description").asText("")));
        }
        return versions;
    }

    List<IssueRaw> fetchBugIssues() throws IOException, InterruptedException {
        List<IssueRaw> issues = new ArrayList<>();
        int startAt = 0;
        int total = Integer.MAX_VALUE;

        while (startAt < total) {
            String query = "jql=" + urlEncode(JQL)
                    + "&startAt=" + startAt
                    + "&maxResults=" + PAGE_SIZE
                    + "&fields=" + urlEncode(ISSUE_FIELDS);

            JsonNode page = fetchJson(URI.create(JIRA_SEARCH_ENDPOINT + "?" + query));
            total = page.path("total").asInt(0);
            JsonNode issueNodes = page.path("issues");
            if (!issueNodes.isArray() || issueNodes.isEmpty()) {
                break;
            }

            for (JsonNode issueNode : issueNodes) {
                issues.add(parseIssue(issueNode));
            }
            startAt += issueNodes.size();
            LOGGER.info("Ticket scaricati: " + issues.size() + " / " + total);
        }
        return issues;
    }

    private IssueRaw parseIssue(final JsonNode issueNode) {
        JsonNode fields = issueNode.path("fields");
        String createdText = fields.path("created").asText("");
        String closedText = fields.path("resolutiondate").asText("");

        return new IssueRaw(
                issueNode.path("id").asText(""),
                issueNode.path("key").asText(""),
                fields.path("summary").asText(""),
                fields.path("status").path("name").asText(""),
                fields.path("resolution").path("name").asText(""),
                fields.path("priority").path("name").asText(""),
                parseJiraDate(createdText).orElse(null),
                parseJiraDate(closedText).orElse(null),
                extractVersionNames(fields.path("versions")),
                extractVersionNames(fields.path("fixVersions")));
    }

    private JsonNode fetchJson(final URI uri) throws IOException, InterruptedException {
        IOException lastIOException = null;

        for (int attempt = 1; attempt <= MAX_HTTP_ATTEMPTS; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(Duration.ofSeconds(90))
                        .header("Accept", "application/json")
                        .header("User-Agent", "Syncope-Milestone-1-Dataset-Builder")
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(
                        request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                int statusCode = response.statusCode();
                if (statusCode >= 200 && statusCode < 300) {
                    return JSON.readTree(response.body());
                }

                if ((statusCode == 429 || statusCode >= 500)
                        && attempt < MAX_HTTP_ATTEMPTS) {
                    Thread.sleep(Duration.ofSeconds(attempt * 2L).toMillis());
                    continue;
                }

                throw new IllegalStateException(
                        "JIRA ha restituito HTTP " + statusCode + " per " + uri
                                + System.lineSeparator() + response.body());
            } catch (IOException exception) {
                lastIOException = exception;
                if (attempt < MAX_HTTP_ATTEMPTS) {
                    Thread.sleep(Duration.ofSeconds(attempt * 2L).toMillis());
                }
            }
        }

        throw lastIOException == null
                ? new IOException("Impossibile interrogare JIRA: " + uri)
                : lastIOException;
    }

    private static List<String> extractVersionNames(final JsonNode versionsNode) {
        List<String> result = new ArrayList<>();
        if (!versionsNode.isArray()) {
            return result;
        }
        for (JsonNode versionNode : versionsNode) {
            String name = versionNode.path("name").asText("").trim();
            if (!name.isBlank()) {
                result.add(name);
            }
        }
        return result;
    }

    private static Optional<OffsetDateTime> parseJiraDate(final String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        for (DateTimeFormatter formatter : JIRA_DATE_FORMATTERS) {
            try {
                return Optional.of(OffsetDateTime.parse(value, formatter));
            } catch (DateTimeParseException ignored) {
                // Prova il formato successivo.
            }
        }
        return Optional.empty();
    }

    private static String urlEncode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

