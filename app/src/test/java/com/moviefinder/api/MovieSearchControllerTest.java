package com.moviefinder.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.moviefinder.search.MovieSearchService;
import com.moviefinder.search.PersonResolver;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class MovieSearchControllerTest {

    private final ObjectMapper json = new ObjectMapper();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MovieSearchController(
            new MovieSearchService(new PersonResolver((tier, name) -> {
                if (tier != PersonResolver.MatchTier.EXACT) {
                    return new PersonResolver.SearchMatches(List.of(), 0);
                }
                if ("Alex Carter".equals(name)) {
                    return new PersonResolver.SearchMatches(List.of(
                            new PersonResolver.PersonCandidate("nm10", "Alex Carter"),
                            new PersonResolver.PersonCandidate("nm11", "Alex Carter")), 2);
                }
                String id = switch (name) {
                    case "Leonardo DiCaprio" -> "nm1";
                    case "Christopher Nolan" -> "nm2";
                    default -> null;
                };
                return id == null ? new PersonResolver.SearchMatches(List.of(), 0)
                        : new PersonResolver.SearchMatches(List.of(
                                new PersonResolver.PersonCandidate(id, name)), 1);
            }), spec -> {
                if ("Unknown Title".equals(spec.title())) {
                    return new MovieSearchService.MoviePage(List.of(), 0, false);
                }
                MovieSearchService.MovieDocument first = new MovieSearchService.MovieDocument(
                        "tt1", "Inception", "Inception", List.of("A Dream Within a Dream"),
                        2010, 8.8, 2_000_000L);
                MovieSearchService.MovieDocument second = new MovieSearchService.MovieDocument(
                        "tt2", "Inception 2", "Inception 2", List.of(), 2024, null, null);
                return spec.afterId() == null
                        ? new MovieSearchService.MoviePage(List.of(first), 2, true)
                        : new MovieSearchService.MoviePage(List.of(second), 2, false);
            }))).setControllerAdvice(new ApiExceptionHandler()).build();

    @Test
    void acceptsTitleActorDirectorAndCompositeRequests() throws Exception {
        for (String body : List.of(
                "{\"name\":\"Inception\",\"pageSize\":1}",
                "{\"actors\":[\"Leonardo DiCaprio\"],\"pageSize\":1}",
                "{\"directors\":[\"Christopher Nolan\"],\"pageSize\":1}",
                "{\"name\":\"Inception\",\"actors\":[\"Leonardo DiCaprio\"],"
                        + "\"directors\":[\"Christopher Nolan\"],\"pageSize\":1}")) {
            JsonNode response = send(body, 200);
            assertThat(response.path("movies").size()).isEqualTo(1);
            assertThat(response.path("movies").get(0).path("id").asText()).isEqualTo("tt1");
            assertThat(response.path("pagination").path("pageSize").asInt()).isEqualTo(1);
            assertThat(response.path("pagination").path("totalMatches").asLong()).isEqualTo(2);
            assertThat(response.path("pagination").path("nextCursor").asText()).isNotBlank();
        }
    }

    @Test
    void reportsMatchedAttributesAndPaginatesWithoutRepeatingTheFirstMovie() throws Exception {
        String query = "{\"name\":\"Inception\",\"actors\":[\"Leonardo DiCaprio\"],"
                + "\"directors\":[\"Christopher Nolan\"],\"pageSize\":1}";
        JsonNode first = send(query, 200);
        JsonNode movie = first.path("movies").get(0);
        assertThat(movie.path("title").asText()).isEqualTo("Inception");
        assertThat(movie.path("year").asInt()).isEqualTo(2010);
        assertThat(movie.path("rating").asDouble()).isEqualTo(8.8);
        assertThat(movie.path("voteCount").asLong()).isEqualTo(2_000_000);
        assertThat(movie.path("matchedAttributes").path("nameMatch").asText())
                .isEqualTo("PRIMARY_TITLE");
        assertThat(movie.path("matchedAttributes").path("actors").get(0).asText())
                .isEqualTo("Leonardo DiCaprio");
        assertThat(movie.path("matchedAttributes").path("directors").get(0).asText())
                .isEqualTo("Christopher Nolan");
        String cursor = first.path("pagination").path("nextCursor").asText();
        JsonNode second = send(query.substring(0, query.length() - 1)
                + ",\"cursor\":\"" + cursor + "\"}", 200);
        assertThat(second.path("movies").get(0).path("id").asText()).isEqualTo("tt2");
        assertThat(second.path("pagination").path("hasMore").asBoolean()).isFalse();
        assertThat(second.path("pagination").path("nextCursor").isNull()).isTrue();
        send("{\"name\":\"Different\",\"cursor\":\"" + cursor + "\"}", 400);
    }

    @Test
    void rejectsInvalidRequestsAndExplainsPersonResolutionFailures() throws Exception {
        assertThat(send("{}", 400).path("detail").asText()).contains("at least one");
        assertThat(send("{\"name\":\"Inception\",\"pageSize\":101}", 400)
                .path("detail").asText()).contains("pageSize");
        assertThat(send("{\"actors\":[\"\"]}", 400).path("detail").asText())
                .contains("non-blank");
        assertThat(send("{\"actors\":\"Leonardo DiCaprio\"}", 400)
                .path("detail").asText()).contains("valid JSON");
        assertThat(send("{\"actors\":[\"Missing Person\"]}", 404).path("title").asText())
                .isEqualTo("Person not found");
        JsonNode ambiguous = send("{\"directors\":[\"Alex Carter\"]}", 409);
        assertThat(ambiguous.path("title").asText()).isEqualTo("Ambiguous person");
        assertThat(ambiguous.path("candidates").size()).isEqualTo(2);
        assertThat(send("{\"name\":\"Unknown Title\"}", 200)
                .path("movies").size()).isZero();
    }

    private JsonNode send(String body, int status) throws Exception {
        var response = mvc.perform(post("/api/movies/search")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(status);
        return json.readTree(response.getContentAsString());
    }
}
