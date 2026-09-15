package com.moviefinder.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Searches the people index in exact, prefix, then limited fuzzy match tiers. */
public final class OpenSearchPersonLookup implements PersonResolver.PersonLookup {

    private static final int CANDIDATE_LIMIT = 20;
    private final OpenSearchSearchClient client;

    public OpenSearchPersonLookup(OpenSearchSearchClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public PersonResolver.SearchMatches search(PersonResolver.MatchTier tier, String name) throws IOException {
        ObjectNode request = client.objectNode();
        request.put("size", CANDIDATE_LIMIT);
        request.put("track_total_hits", true);
        ArrayNode source = request.putArray("_source");
        source.add("id").add("primaryName");
        ArrayNode sort = request.putArray("sort");
        if (tier != PersonResolver.MatchTier.EXACT) {
            sort.addObject().put("_score", "desc");
        }
        sort.addObject().put("id", "asc");

        ObjectNode query = request.putObject("query");
        switch (tier) {
            case EXACT -> {
                ObjectNode bool = query.putObject("bool");
                bool.put("minimum_should_match", 1);
                ArrayNode should = bool.putArray("should");
                should.addObject().putObject("term").put("primaryName.raw", name);
                should.addObject().putObject("term")
                        .put("normalizedName", PersonResolver.normalizeName(name));
            }
            case PREFIX -> query.putObject("match").putObject("primaryName.prefix")
                    .put("query", name).put("operator", "AND");
            case FUZZY -> query.putObject("match").putObject("primaryName")
                    .put("query", name).put("operator", "AND")
                    .put("fuzziness", "AUTO").put("prefix_length", 1)
                    .put("max_expansions", 25);
        }

        JsonNode hits = OpenSearchSearchClient.hits(client.search("people", request));
        long total = hits.path("total").path("value").asLong();
        List<PersonResolver.PersonCandidate> candidates = new ArrayList<>();
        for (JsonNode hit : hits.path("hits")) {
            JsonNode person = hit.path("_source");
            String id = person.path("id").asText();
            String displayName = person.path("primaryName").asText();
            if (!id.matches("nm\\d+") || displayName.isBlank()) {
                throw new IOException("invalid person in OpenSearch search response");
            }
            candidates.add(new PersonResolver.PersonCandidate(id, displayName));
        }
        return new PersonResolver.SearchMatches(candidates, total);
    }
}
