package com.moviefinder.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Reads movie documents through keyword ID filters; movie names are never used for this query. */
public final class OpenSearchMovieLookup implements MoviePersonSearch.MovieLookup {

    private final OpenSearchSearchClient client;

    public OpenSearchMovieLookup(OpenSearchSearchClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public MoviePersonSearch.MovieMatches findMovies(String personId, MoviePersonSearch.Role role, int limit)
            throws IOException {
        if (personId == null || !personId.matches("nm\\d+")) {
            throw new IllegalArgumentException("canonical IMDb person ID is required");
        }
        Objects.requireNonNull(role, "role");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("movie search limit must be between 1 and 100");
        }
        ObjectNode request = client.objectNode();
        request.put("size", limit);
        request.put("track_total_hits", true);
        ArrayNode source = request.putArray("_source");
        source.add("id").add("primaryTitle").add("releaseYear")
                .add("averageRating").add("voteCount");
        request.putObject("query").putObject("term").put(role.indexField(), personId);
        request.putArray("sort").addObject().put("id", "asc");

        JsonNode hits = OpenSearchSearchClient.hits(client.search("movies", request));
        List<MoviePersonSearch.MovieCandidate> candidates = new ArrayList<>();
        for (JsonNode hit : hits.path("hits")) {
            JsonNode movie = hit.path("_source");
            String id = movie.path("id").asText();
            String title = movie.path("primaryTitle").asText();
            if (!id.matches("tt\\d+") || title.isBlank()) {
                throw new IOException("invalid movie in OpenSearch search response");
            }
            JsonNode year = movie.path("releaseYear");
            JsonNode rating = movie.path("averageRating");
            JsonNode votes = movie.path("voteCount");
            candidates.add(new MoviePersonSearch.MovieCandidate(id, title,
                    year.isNumber() ? year.asInt() : null,
                    rating.isNumber() ? rating.asDouble() : null,
                    votes.isNumber() ? votes.asLong() : null));
        }
        return new MoviePersonSearch.MovieMatches(candidates, hits.path("total").path("value").asLong());
    }
}
