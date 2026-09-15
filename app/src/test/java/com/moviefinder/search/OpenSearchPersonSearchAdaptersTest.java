package com.moviefinder.search;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OpenSearchPersonSearchAdaptersTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void sendsTieredPeopleQueriesThenAnExactCastIdFilter() throws IOException {
        List<String> peopleQueries = new ArrayList<>();
        List<String> movieQueries = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/people/_search", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
            peopleQueries.add(body);
            String response = body.contains("\"fuzziness\"")
                    ? "{\"hits\":{\"total\":{\"value\":1},\"hits\":[{\"_source\":{\"id\":\"nm0000138\",\"primaryName\":\"Leonardo DiCaprio\"}}]}}"
                    : "{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}";
            send(exchange, response);
        });
        server.createContext("/movies/_search", exchange -> {
            movieQueries.add(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
            send(exchange, "{\"hits\":{\"total\":{\"value\":1},\"hits\":[{\"_source\":{\"id\":\"tt1375666\",\"primaryTitle\":\"Inception\",\"releaseYear\":2010,\"averageRating\":8.8,\"voteCount\":2000000}}]}}");
        });
        server.start();
        try {
            URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            OpenSearchSearchClient client = new OpenSearchSearchClient(url);
            MoviePersonSearch search = new MoviePersonSearch(
                    new PersonResolver(new OpenSearchPersonLookup(client)),
                    new OpenSearchMovieLookup(client));

            MoviePersonSearch.SearchResult result = search.search("Leonrdo DiCaprio",
                    MoviePersonSearch.Role.CAST, 10);

            assertThat(result.resolution().person().id()).isEqualTo("nm0000138");
            assertThat(result.movies().candidates()).extracting(MoviePersonSearch.MovieCandidate::id)
                    .containsExactly("tt1375666");
            assertThat(peopleQueries).hasSize(3);
            JsonNode exact = json.readTree(peopleQueries.get(0));
            assertThat(exact.path("query").path("bool").path("should").get(0)
                    .path("term").path("primaryName.raw").asText()).isEqualTo("Leonrdo DiCaprio");
            assertThat(exact.path("query").path("bool").path("should").get(1)
                    .path("term").path("normalizedName").asText()).isEqualTo("leonrdo dicaprio");
            assertThat(exact.path("track_total_hits").asBoolean()).isTrue();
            JsonNode prefix = json.readTree(peopleQueries.get(1)).path("query")
                    .path("match").path("primaryName.prefix");
            assertThat(prefix.path("query").asText()).isEqualTo("Leonrdo DiCaprio");
            assertThat(prefix.path("operator").asText()).isEqualTo("AND");
            JsonNode fuzzy = json.readTree(peopleQueries.get(2)).path("query")
                    .path("match").path("primaryName");
            assertThat(fuzzy.path("fuzziness").asText()).isEqualTo("AUTO");
            assertThat(fuzzy.path("prefix_length").asInt()).isEqualTo(1);
            assertThat(fuzzy.path("max_expansions").asInt()).isEqualTo(25);
            assertThat(movieQueries).hasSize(1);
            JsonNode movie = json.readTree(movieQueries.getFirst());
            assertThat(movie.path("query").path("term").path("castPersonIds").asText())
                    .isEqualTo("nm0000138");
            assertThat(movie.path("size").asInt()).isEqualTo(10);
        } finally {
            server.stop(0);
        }
    }

    private static void send(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] response = body.getBytes(UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
