package com.moviefinder.search;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;

import com.moviefinder.ingestion.ImdbDatasetCatalog;
import com.moviefinder.ingestion.ImdbDataset;
import com.moviefinder.ingestion.ImdbIngestionPipeline;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
class OpenSearchReadModelIntegrationTest {

    @Container
    static final GenericContainer<?> opensearch = new GenericContainer<>(
            DockerImageName.parse("opensearchproject/opensearch:3.8.0"))
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/").forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(4));

    @TempDir
    Path temp;

    private final ObjectMapper json = new ObjectMapper();
    private OpenSearchReadModel model;
    private URI url;

    @BeforeEach
    void recreateIndexes() throws IOException {
        url = URI.create("http://" + opensearch.getHost() + ":" + opensearch.getMappedPort(9200) + "/");
        model = new OpenSearchReadModel(url);
        model.createIndexes(true);
    }

    @Test
    void createsVersionedMappingsWithKeywordIdsAndSearchAnalyzers() throws IOException, InterruptedException {
        model.createIndexes(false);
        JsonNode people = get("people/_mapping").path("people").path("mappings").path("properties");
        JsonNode movies = get("movies/_mapping").path("movies").path("mappings").path("properties");

        assertThat(people.path("id").path("type").asText()).isEqualTo("keyword");
        assertThat(people.path("knownForTitleIds").path("type").asText()).isEqualTo("keyword");
        assertThat(people.path("primaryName").path("analyzer").asText()).isEqualTo("name_text");
        assertThat(people.path("primaryName").path("fields").path("raw").path("type").asText())
                .isEqualTo("keyword");
        assertThat(people.path("primaryName").path("fields").path("prefix").path("search_analyzer").asText())
                .isEqualTo("name_text");
        assertThat(movies.path("id").path("type").asText()).isEqualTo("keyword");
        assertThat(movies.path("castPersonIds").path("type").asText()).isEqualTo("keyword");
        assertThat(movies.path("directorPersonIds").path("type").asText()).isEqualTo("keyword");
        assertThat(movies.path("primaryTitle").path("fields").path("prefix").path("analyzer").asText())
                .isEqualTo("title_prefix");
        assertThat(movies.path("aliases").path("type").asText()).isEqualTo("text");
    }

    @Test
    void bulkIndexesPipelineRecordsAndResolvesCastAndDirectorNames() throws IOException, InterruptedException {
        Path raw = Files.createDirectory(temp.resolve("raw"));
        for (ImdbDataset dataset : ImdbDatasetCatalog.datasets()) {
            try (var resource = getClass().getResourceAsStream("/imdb/" + dataset.filename())) {
                assertThat(resource).isNotNull();
                Files.copy(resource, raw.resolve(dataset.filename()));
            }
        }
        Path canonical = temp.resolve("canonical");
        new ImdbIngestionPipeline(new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())).importAll(raw, canonical);

        OpenSearchReadModel.IndexSummary summary = model.indexCanonical(canonical, false);
        assertThat(summary.movies()).isEqualTo(2);
        assertThat(summary.people()).isEqualTo(4);
        post("people/_refresh", "{}");
        post("movies/_refresh", "{}");

        JsonNode movie = get("movies/_doc/tt9000000001").path("_source");
        assertThat(movie.path("id").asText()).isEqualTo("tt9000000001");
        assertThat(movie.path("castNames").toString()).contains("Avery Quill", "Sol Rivera");
        assertThat(movie.path("directorNames").toString()).contains("Rowan Pike");
        assertThat(movie.path("averageRating").asDouble()).isEqualTo(7.4);
        assertThat(movie.path("voteCount").asLong()).isEqualTo(1842);

        assertThat(hitCount("people/_search", "{\"query\":{\"term\":{\"id\":\"nm9000000002\"}}}"))
                .isEqualTo(1);
        assertThat(hitCount("movies/_search", "{\"query\":{\"term\":{\"castPersonIds\":\"nm9000000002\"}}}"))
                .isEqualTo(2);
        assertThat(hitCount("movies/_search", "{\"query\":{\"match\":{\"primaryTitle.prefix\":\"clock\"}}}"))
                .isEqualTo(1);
        assertThat(hitCount("movies/_search", "{\"query\":{\"match\":{\"aliases\":\"porto\"}}}"))
                .isEqualTo(1);
        assertThat(hitCount("movies/_search", "{\"query\":{\"match\":{\"primaryTitle\":{\"query\":\"Clockwrok\",\"fuzziness\":\"AUTO\"}}}}"))
                .isEqualTo(1);

        model.createIndexes(true);
        assertThat(get("movies/_count").path("count").asLong()).isZero();
        assertThat(get("people/_count").path("count").asLong()).isZero();
    }

    private long hitCount(String path, String body) throws IOException, InterruptedException {
        return post(path, body).path("hits").path("total").path("value").asLong();
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        return send("GET", path, null);
    }

    private JsonNode post(String path, String body) throws IOException, InterruptedException {
        return send("POST", path, body);
    }

    private JsonNode send(String method, String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(url.resolve(path))
                .timeout(Duration.ofSeconds(30));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, UTF_8));
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request.build(), HttpResponse.BodyHandlers.ofString(UTF_8));
        assertThat(response.statusCode()).isBetween(200, 299);
        return json.readTree(response.body());
    }
}
