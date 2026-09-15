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

class OpenSearchCompositeMovieLookupTest {

    @Test
    void sendsOneAndQueryAndUsesSearchAfterForTheNextPage() throws IOException {
        List<String> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/movies/_search", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
            requests.add(body);
            String first = "{\"_source\":{\"id\":\"tt1\",\"primaryTitle\":\"Inception\","
                    + "\"originalTitle\":\"Inception\",\"aliases\":[\"Dreams Begin\"],"
                    + "\"releaseYear\":2010,\"averageRating\":8.8,\"voteCount\":2000000}}";
            String second = "{\"_source\":{\"id\":\"tt2\",\"primaryTitle\":\"Inception Echo\","
                    + "\"aliases\":[],\"releaseYear\":2020}}";
            String response = "{\"hits\":{\"total\":{\"value\":2},\"hits\":["
                    + (body.contains("search_after") ? second : first + "," + second) + "]}}";
            byte[] bytes = response.getBytes(UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            OpenSearchCompositeMovieLookup lookup = new OpenSearchCompositeMovieLookup(
                    new OpenSearchSearchClient(URI.create("http://127.0.0.1:"
                            + server.getAddress().getPort())));
            MovieSearchService.MoviePage first = lookup.search(new MovieSearchService.SearchSpec(
                    "Inception", List.of("nm1", "nm3"), List.of("nm2"), 1, null));
            assertThat(first.movies()).extracting(MovieSearchService.MovieDocument::id)
                    .containsExactly("tt1");
            assertThat(first.movies().getFirst().aliases()).containsExactly("Dreams Begin");
            assertThat(first.hasMore()).isTrue();
            assertThat(first.total()).isEqualTo(2);

            JsonNode query = new ObjectMapper().readTree(requests.getFirst());
            JsonNode filters = query.path("query").path("bool").path("filter");
            assertThat(filters.size()).isEqualTo(4);
            assertThat(filters.get(0).path("bool").path("minimum_should_match").asInt())
                    .isEqualTo(1);
            assertThat(filters.get(0).path("bool").path("should").toString())
                    .contains("primaryTitle", "aliases", "Inception");
            assertThat(filters.get(1).path("term").path("castPersonIds").asText()).isEqualTo("nm1");
            assertThat(filters.get(2).path("term").path("castPersonIds").asText()).isEqualTo("nm3");
            assertThat(filters.get(3).path("term").path("directorPersonIds").asText())
                    .isEqualTo("nm2");
            assertThat(query.path("size").asInt()).isEqualTo(2);
            assertThat(query.path("sort").get(0).path("id").asText()).isEqualTo("asc");

            MovieSearchService.MoviePage second = lookup.search(new MovieSearchService.SearchSpec(
                    "Inception", List.of("nm1", "nm3"), List.of("nm2"), 1, "tt1"));
            assertThat(second.movies()).extracting(MovieSearchService.MovieDocument::id)
                    .containsExactly("tt2");
            assertThat(second.hasMore()).isFalse();
            assertThat(new ObjectMapper().readTree(requests.getLast())
                    .path("search_after").get(0).asText()).isEqualTo("tt1");
        } finally {
            server.stop(0);
        }
    }
}
