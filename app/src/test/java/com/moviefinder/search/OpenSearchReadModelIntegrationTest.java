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
import java.util.List;

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

    @Test
    void resolvesNamesAndFiltersMoviesByCanonicalPersonIds() throws IOException, InterruptedException {
        Path canonical = Files.createDirectory(temp.resolve("resolution-canonical"));
        Files.writeString(canonical.resolve("people.ndjson"), """
                {"id":"nm0000138","primaryName":"Leonardo DiCaprio","normalizedName":"leonardo dicaprio","professions":["actor"],"knownForTitleIds":["tt1375666"]}
                {"id":"nm8000001","primaryName":"Christopher Nolan","normalizedName":"christopher nolan","professions":["director"],"knownForTitleIds":["tt1375666"]}
                {"id":"nm1000001","primaryName":"Alex Carter","normalizedName":"alex carter","professions":["actor"],"knownForTitleIds":["tt9000000001"]}
                {"id":"nm1000002","primaryName":"Alex Carter","normalizedName":"alex carter","professions":["director"],"knownForTitleIds":["tt9000000001"]}
                """, UTF_8);
        Files.writeString(canonical.resolve("movies.ndjson"), """
                {"id":"tt1375666","primaryTitle":"Inception","originalTitle":"Inception","aliases":[],"releaseYear":2010,"castPersonIds":["nm0000138"],"directorPersonIds":["nm8000001"],"genres":["Sci-Fi"],"averageRating":8.8,"voteCount":2000000}
                {"id":"tt9000000001","primaryTitle":"Other Movie","originalTitle":"Other Movie","aliases":[],"releaseYear":2020,"castPersonIds":["nm1000001"],"directorPersonIds":["nm1000002"],"genres":[],"averageRating":null,"voteCount":null}
                """, UTF_8);
        model.indexCanonical(canonical, false);
        post("people/_refresh", "{}");
        post("movies/_refresh", "{}");

        OpenSearchSearchClient client = new OpenSearchSearchClient(url);
        PersonResolver resolver = new PersonResolver(new OpenSearchPersonLookup(client));
        MoviePersonSearch search = new MoviePersonSearch(resolver, new OpenSearchMovieLookup(client));

        assertThat(resolver.resolve("Leonardo DiCaprio").person().id()).isEqualTo("nm0000138");
        assertThat(resolver.resolve("LEONARDO DICAPRIO").person().id()).isEqualTo("nm0000138");
        assertThat(resolver.resolve("  Leonardo   DiCaprio  ").person().id()).isEqualTo("nm0000138");
        assertThat(resolver.resolve("Leonardo Di").matchTier()).isEqualTo(PersonResolver.MatchTier.PREFIX);
        PersonResolver.Resolution typo = resolver.resolve("Leonrdo DiCaprio");
        assertThat(typo.matchTier()).isEqualTo(PersonResolver.MatchTier.FUZZY);
        assertThat(typo.person().id()).isEqualTo("nm0000138");
        assertThat(resolver.resolve("Alex Carter").status()).isEqualTo(PersonResolver.Status.AMBIGUOUS);
        assertThat(resolver.resolve("Alex Carter").candidates())
                .extracting(PersonResolver.PersonCandidate::id).containsExactly("nm1000001", "nm1000002");
        assertThat(resolver.resolve("zzzzxxxx yyyyqqqq").status()).isEqualTo(PersonResolver.Status.NOT_FOUND);

        MoviePersonSearch.SearchResult cast = search.search("Leonardo DiCaprio",
                MoviePersonSearch.Role.CAST, 10);
        assertThat(cast.movies().candidates()).extracting(MoviePersonSearch.MovieCandidate::id)
                .containsExactly("tt1375666");
        assertThat(cast.movies().total()).isEqualTo(1);
        assertThat(search.search("Christopher Nolan", MoviePersonSearch.Role.DIRECTOR, 10)
                .movies().candidates()).extracting(MoviePersonSearch.MovieCandidate::id)
                .containsExactly("tt1375666");
        assertThat(search.search("Leonardo DiCaprio", MoviePersonSearch.Role.DIRECTOR, 10)
                .movies().candidates()).isEmpty();
        assertThat(search.search("Alex Carter", MoviePersonSearch.Role.CAST, 10)
                .resolution().status()).isEqualTo(PersonResolver.Status.AMBIGUOUS);
    }

    @Test
    void compositeSearchUsesTitlesAliasesAllPersonIdsAndStableCursorPages()
            throws IOException, InterruptedException {
        Path canonical = Files.createDirectory(temp.resolve("composite-canonical"));
        Files.writeString(canonical.resolve("people.ndjson"), """
                {"id":"nm1001","primaryName":"Leonardo DiCaprio","normalizedName":"leonardo dicaprio","professions":["actor"],"knownForTitleIds":[]}
                {"id":"nm1002","primaryName":"Christopher Nolan","normalizedName":"christopher nolan","professions":["director"],"knownForTitleIds":[]}
                {"id":"nm1003","primaryName":"Kate Example","normalizedName":"kate example","professions":["actress"],"knownForTitleIds":[]}
                {"id":"nm1004","primaryName":"Other Director","normalizedName":"other director","professions":["director"],"knownForTitleIds":[]}
                """, UTF_8);
        Files.writeString(canonical.resolve("movies.ndjson"), """
                {"id":"tt1001","primaryTitle":"Inception","originalTitle":"Inception","aliases":["Dreams Begin"],"releaseYear":2010,"castPersonIds":["nm1001"],"directorPersonIds":["nm1002"],"genres":[],"averageRating":8.8,"voteCount":2000000}
                {"id":"tt1002","primaryTitle":"Inception Echo","originalTitle":"Inception Echo","aliases":[],"releaseYear":2020,"castPersonIds":["nm1001"],"directorPersonIds":["nm1004"],"genres":[],"averageRating":null,"voteCount":null}
                {"id":"tt1003","primaryTitle":"Another Dream","originalTitle":"Another Dream","aliases":["Inception","The Beginning"],"releaseYear":2021,"castPersonIds":["nm1001","nm1003"],"directorPersonIds":["nm1002"],"genres":[],"averageRating":7.0,"voteCount":100}
                {"id":"tt1004","primaryTitle":"No Match","originalTitle":"No Match","aliases":[],"releaseYear":2022,"castPersonIds":["nm1003"],"directorPersonIds":["nm1002"],"genres":[],"averageRating":null,"voteCount":null}
                """, UTF_8);
        model.indexCanonical(canonical, false);
        post("people/_refresh", "{}");
        post("movies/_refresh", "{}");

        OpenSearchSearchClient client = new OpenSearchSearchClient(url);
        MovieSearchService search = new MovieSearchService(
                new PersonResolver(new OpenSearchPersonLookup(client)),
                new OpenSearchCompositeMovieLookup(client));

        assertThat(ids(search.search(request("Inception", null, null, 100, null))))
                .contains("tt1001", "tt1002", "tt1003");
        assertThat(ids(search.search(request("The Beginning", null, null, 100, null))))
                .containsExactly("tt1003");
        assertThat(ids(search.search(request(null, List.of("Leonardo DiCaprio"), null, 100, null))))
                .containsExactly("tt1001", "tt1002", "tt1003");
        assertThat(ids(search.search(request(null, null, List.of("Christopher Nolan"), 100, null))))
                .containsExactly("tt1001", "tt1003", "tt1004");
        assertThat(ids(search.search(request("Inception", List.of("Leonardo DiCaprio"),
                List.of("Christopher Nolan"), 100, null))))
                .containsExactly("tt1001", "tt1003");
        assertThat(ids(search.search(request(null, List.of("Leonardo DiCaprio", "Kate Example"),
                List.of("Christopher Nolan"), 100, null))))
                .containsExactly("tt1003");
        assertThat(ids(search.search(request("No Match", List.of("Leonardo DiCaprio"),
                List.of("Christopher Nolan"), 100, null)))).isEmpty();

        String cursor = null;
        List<String> paged = new java.util.ArrayList<>();
        do {
            MovieSearchService.SearchResponse page = search.search(request(null,
                    List.of("Leonardo DiCaprio"), null, 1, cursor));
            paged.addAll(ids(page));
            assertThat(page.pagination().totalMatches()).isEqualTo(3);
            cursor = page.pagination().nextCursor();
        } while (cursor != null);
        assertThat(paged).containsExactly("tt1001", "tt1002", "tt1003");
    }

    private static MovieSearchService.SearchRequest request(String title, List<String> actors,
            List<String> directors, int pageSize, String cursor) {
        return new MovieSearchService.SearchRequest(title, actors, directors, pageSize, cursor);
    }

    private static List<String> ids(MovieSearchService.SearchResponse response) {
        return response.movies().stream().map(MovieSearchService.MovieResult::id).toList();
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
