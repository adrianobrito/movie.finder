package com.moviefinder.search;

import java.io.IOException;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Resolves a supplied name to a person ID without choosing between ambiguous people. */
public final class PersonResolver {

    private final PersonLookup lookup;

    public PersonResolver(PersonLookup lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    public Resolution resolve(String name) throws IOException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("person name is required");
        }
        String query = name.strip();
        for (MatchTier tier : MatchTier.values()) {
            SearchMatches matches = lookup.search(tier, query);
            if (matches.total() > 0) {
                Status status = matches.total() == 1 ? Status.RESOLVED : Status.AMBIGUOUS;
                return new Resolution(status, tier, matches.candidates(), matches.total());
            }
        }
        return new Resolution(Status.NOT_FOUND, null, List.of(), 0);
    }

    static String normalizeName(String name) {
        String decomposed = Normalizer.normalize(name, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    public interface PersonLookup {
        SearchMatches search(MatchTier tier, String name) throws IOException;
    }

    public enum MatchTier {
        EXACT, PREFIX, FUZZY
    }

    public enum Status {
        RESOLVED, AMBIGUOUS, NOT_FOUND
    }

    public record PersonCandidate(String id, String displayName) {
        public PersonCandidate {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(displayName, "displayName");
        }
    }

    /** total is the full hit count; candidates may contain only the first page of an ambiguous set. */
    public record SearchMatches(List<PersonCandidate> candidates, long total) {
        public SearchMatches {
            candidates = List.copyOf(candidates);
            if (total < candidates.size() || total < 0 || total > 0 && candidates.isEmpty()) {
                throw new IllegalArgumentException("invalid person search result");
            }
        }
    }

    public record Resolution(Status status, MatchTier matchTier, List<PersonCandidate> candidates,
            long totalCandidates) {
        public Resolution {
            candidates = List.copyOf(candidates);
            if (status == Status.RESOLVED && (totalCandidates != 1 || candidates.size() != 1)) {
                throw new IllegalArgumentException("resolved person must have exactly one candidate");
            }
            if (status == Status.AMBIGUOUS && (totalCandidates < 2 || candidates.isEmpty())) {
                throw new IllegalArgumentException("ambiguous person must have multiple matches");
            }
            if (status == Status.NOT_FOUND && (totalCandidates != 0 || !candidates.isEmpty())) {
                throw new IllegalArgumentException("missing person must have no candidates");
            }
        }

        public PersonCandidate person() {
            if (status != Status.RESOLVED) {
                throw new IllegalStateException("person is not uniquely resolved: " + status);
            }
            return candidates.getFirst();
        }
    }
}
