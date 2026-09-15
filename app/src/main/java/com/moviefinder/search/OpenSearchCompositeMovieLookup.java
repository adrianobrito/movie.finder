package com.moviefinder.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Executes one AND query and pages by the keyword movie ID. */
public final class OpenSearchCompositeMovieLookup implements MovieSearchService.MovieLookup {

    private final OpenSearchSearchClient client;

    public OpenSearchCompositeMovieLookup(OpenSearchSearchClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public MovieSearchService.MoviePage search(MovieSearchService.SearchSpec spec) throws IOException {
        ObjectNode request = client.objectNode();
        request.put("size", spec.pageSize() + 1);
        request.put("track_total_hits", true);
        request.putArray("_source").add("id").add("primaryTitle").add("originalTitle")
                .add("aliases").add("releaseYear").add("averageRating").add("voteCount");
        request.putArray("sort").addObject().put("id", "asc");
        if (spec.afterId() != null) {
            request.putArray("search_after").add(spec.afterId());
        }

        ArrayNode filters = request.putObject("query").putObject("bool").putArray("filter");
        if (spec.title() != null) {
            ObjectNode title = filters.addObject().putObject("bool");
            title.put("minimum_should_match", 1);
            ArrayNode should = title.putArray("should");
            should.addObject().putObject("term").put("primaryTitle.raw",
                    PersonResolver.normalizeName(spec.title()));
            phrase(should, "primaryTitle", spec.title());
            phrase(should, "originalTitle", spec.title());
            phrase(should, "aliases", spec.title());
            text(should, "primaryTitle.prefix", spec.title(), false);
            text(should, "aliases.prefix", spec.title(), false);
            text(should, "primaryTitle", spec.title(), true);
            text(should, "originalTitle", spec.title(), true);
            text(should, "aliases", spec.title(), true);
        }
        for (String personId : spec.actorIds()) {
            filters.addObject().putObject("term").put("castPersonIds", personId);
        }
        for (String personId : spec.directorIds()) {
            filters.addObject().putObject("term").put("directorPersonIds", personId);
        }

        JsonNode hits = OpenSearchSearchClient.hits(client.search("movies", request));
        List<MovieSearchService.MovieDocument> movies = new ArrayList<>();
        for (JsonNode hit : hits.path("hits")) {
            JsonNode movie = hit.path("_source");
            String id = movie.path("id").asText();
            String title = movie.path("primaryTitle").asText();
            if (!id.matches("tt\\d+") || title.isBlank()) {
                throw new IOException("invalid movie in OpenSearch search response");
            }
            List<String> aliases = new ArrayList<>();
            for (JsonNode alias : movie.path("aliases")) {
                aliases.add(alias.asText());
            }
            JsonNode year = movie.path("releaseYear");
            JsonNode rating = movie.path("averageRating");
            JsonNode votes = movie.path("voteCount");
            movies.add(new MovieSearchService.MovieDocument(id, title,
                    movie.path("originalTitle").isString() ? movie.path("originalTitle").asText() : null,
                    aliases, year.isNumber() ? year.asInt() : null,
                    rating.isNumber() ? rating.asDouble() : null,
                    votes.isNumber() ? votes.asLong() : null));
        }
        boolean hasMore = movies.size() > spec.pageSize();
        if (hasMore) {
            movies.removeLast();
        }
        return new MovieSearchService.MoviePage(movies,
                hits.path("total").path("value").asLong(), hasMore);
    }

    private static void phrase(ArrayNode should, String field, String query) {
        should.addObject().putObject("match_phrase").put(field, query);
    }

    private static void text(ArrayNode should, String field, String query, boolean fuzzy) {
        ObjectNode match = should.addObject().putObject("match").putObject(field)
                .put("query", query).put("operator", "AND");
        if (fuzzy) {
            match.put("fuzziness", "AUTO").put("prefix_length", 1)
                    .put("max_expansions", 25);
        }
    }
}
