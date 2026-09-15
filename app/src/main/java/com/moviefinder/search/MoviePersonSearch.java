package com.moviefinder.search;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Resolves a name first, then filters movie documents by the canonical person ID. */
public final class MoviePersonSearch {

    private final PersonResolver resolver;
    private final MovieLookup movies;

    public MoviePersonSearch(PersonResolver resolver, MovieLookup movies) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.movies = Objects.requireNonNull(movies, "movies");
    }

    public SearchResult search(String name, Role role, int limit) throws IOException {
        Objects.requireNonNull(role, "role");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("movie search limit must be between 1 and 100");
        }
        PersonResolver.Resolution resolution = resolver.resolve(name);
        if (resolution.status() != PersonResolver.Status.RESOLVED) {
            return new SearchResult(resolution, new MovieMatches(List.of(), 0));
        }
        return new SearchResult(resolution, movies.findMovies(resolution.person().id(), role, limit));
    }

    public interface MovieLookup {
        MovieMatches findMovies(String personId, Role role, int limit) throws IOException;
    }

    public enum Role {
        CAST("castPersonIds"), DIRECTOR("directorPersonIds");

        private final String indexField;

        Role(String indexField) {
            this.indexField = indexField;
        }

        String indexField() {
            return indexField;
        }
    }

    public record MovieCandidate(String id, String title, Integer releaseYear,
            Double averageRating, Long voteCount) {
    }

    public record MovieMatches(List<MovieCandidate> candidates, long total) {
        public MovieMatches {
            candidates = List.copyOf(candidates);
            if (total < candidates.size() || total < 0) {
                throw new IllegalArgumentException("invalid movie search result");
            }
        }
    }

    public record SearchResult(PersonResolver.Resolution resolution, MovieMatches movies) {
        public SearchResult {
            Objects.requireNonNull(resolution, "resolution");
            Objects.requireNonNull(movies, "movies");
        }
    }
}
