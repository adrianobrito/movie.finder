package com.moviefinder.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Resolves supplied people before issuing one composite movie query. */
public final class MovieSearchService {

    private final PersonResolver people;
    private final MovieLookup movies;

    public MovieSearchService(PersonResolver people, MovieLookup movies) {
        this.people = Objects.requireNonNull(people, "people");
        this.movies = Objects.requireNonNull(movies, "movies");
    }

    public SearchResponse search(SearchRequest request) throws IOException {
        if (request == null) {
            throw new IllegalArgumentException("search request is required");
        }
        String title = optionalTitle(request.name());
        List<String> actors = names(request.actors(), "actors");
        List<String> directors = names(request.directors(), "directors");
        if (title == null && actors.isEmpty() && directors.isEmpty()) {
            throw new IllegalArgumentException("at least one of name, actors, or directors is required");
        }
        int pageSize = request.pageSize() == null ? 100 : request.pageSize();
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("pageSize must be between 1 and 100");
        }

        List<PersonResolver.PersonCandidate> resolvedActors = resolve(actors, "actor");
        List<PersonResolver.PersonCandidate> resolvedDirectors = resolve(directors, "director");
        String fingerprint = fingerprint(title, resolvedActors, resolvedDirectors);
        String afterId = decodeCursor(request.cursor(), fingerprint);
        MoviePage page = movies.search(new SearchSpec(title,
                resolvedActors.stream().map(PersonResolver.PersonCandidate::id).toList(),
                resolvedDirectors.stream().map(PersonResolver.PersonCandidate::id).toList(),
                pageSize, afterId));

        List<MovieResult> results = new ArrayList<>();
        for (MovieDocument movie : page.movies()) {
            results.add(new MovieResult(movie.id(), movie.title(), movie.year(), movie.rating(),
                    movie.voteCount(), new MatchedAttributes(
                            title == null ? null : titleMatch(title, movie),
                            resolvedActors.stream().map(PersonResolver.PersonCandidate::displayName).toList(),
                            resolvedDirectors.stream().map(PersonResolver.PersonCandidate::displayName).toList())));
        }
        String nextCursor = page.hasMore() && !results.isEmpty()
                ? encodeCursor(fingerprint, results.getLast().id()) : null;
        return new SearchResponse(List.copyOf(results),
                new Pagination(pageSize, results.size(), page.total(), page.hasMore(), nextCursor));
    }

    private List<PersonResolver.PersonCandidate> resolve(List<String> names, String role) throws IOException {
        List<PersonResolver.PersonCandidate> resolved = new ArrayList<>();
        for (String name : names) {
            PersonResolver.Resolution result = people.resolve(name);
            if (result.status() != PersonResolver.Status.RESOLVED) {
                throw new PersonResolutionException(role, name, result);
            }
            if (resolved.stream().noneMatch(person -> person.id().equals(result.person().id()))) {
                resolved.add(result.person());
            }
        }
        return List.copyOf(resolved);
    }

    private static String optionalTitle(String title) {
        if (title == null) {
            return null;
        }
        if (title.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return title.strip();
    }

    private static List<String> names(List<String> input, String field) {
        if (input == null) {
            return List.of();
        }
        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        for (String name : input) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException(field + " must contain non-blank names");
            }
            unique.putIfAbsent(normalize(name), name.strip());
        }
        return List.copyOf(unique.values());
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static String titleMatch(String requested, MovieDocument movie) {
        String normalized = normalize(requested);
        if (normalized.equals(normalize(movie.title()))) {
            return "PRIMARY_TITLE";
        }
        if (movie.originalTitle() != null && normalized.equals(normalize(movie.originalTitle()))) {
            return "ORIGINAL_TITLE";
        }
        if (movie.aliases().stream().anyMatch(alias -> normalized.equals(normalize(alias)))) {
            return "ALIAS";
        }
        return "TEXT";
    }

    private static String fingerprint(String title, List<PersonResolver.PersonCandidate> actors,
            List<PersonResolver.PersonCandidate> directors) {
        String query = (title == null ? "" : normalize(title)) + "\u0000"
                + actors.stream().map(PersonResolver.PersonCandidate::id).sorted().toList() + "\u0000"
                + directors.stream().map(PersonResolver.PersonCandidate::id).sorted().toList();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(query.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String decodeCursor(String cursor, String fingerprint) {
        if (cursor == null) {
            return null;
        }
        if (cursor.isBlank() || cursor.length() > 256) {
            throw new IllegalArgumentException("invalid cursor");
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", -1);
            if (parts.length != 3 || !"v1".equals(parts[0])
                    || !fingerprint.equals(parts[1]) || !parts[2].matches("tt\\d+")) {
                throw new IllegalArgumentException("cursor is invalid or does not match this search");
            }
            return parts[2];
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("cursor is")) {
                throw exception;
            }
            throw new IllegalArgumentException("invalid cursor", exception);
        }
    }

    private static String encodeCursor(String fingerprint, String id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("v1:" + fingerprint + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    public interface MovieLookup {
        MoviePage search(SearchSpec spec) throws IOException;
    }

    public record SearchRequest(String name, List<String> actors, List<String> directors,
            Integer pageSize, String cursor) {
    }

    public record SearchSpec(String title, List<String> actorIds, List<String> directorIds,
            int pageSize, String afterId) {
        public SearchSpec {
            actorIds = List.copyOf(actorIds);
            directorIds = List.copyOf(directorIds);
        }
    }

    public record MovieDocument(String id, String title, String originalTitle, List<String> aliases,
            Integer year, Double rating, Long voteCount) {
        public MovieDocument {
            aliases = List.copyOf(aliases);
        }
    }

    public record MoviePage(List<MovieDocument> movies, long total, boolean hasMore) {
        public MoviePage {
            movies = List.copyOf(movies);
            if (total < movies.size() || total < 0) {
                throw new IllegalArgumentException("invalid movie search page");
            }
        }
    }

    public record MatchedAttributes(String nameMatch, List<String> actors, List<String> directors) {
    }

    public record MovieResult(String id, String title, Integer year, Double rating, Long voteCount,
            MatchedAttributes matchedAttributes) {
    }

    public record Pagination(int pageSize, int returnedCount, long totalMatches, boolean hasMore,
            String nextCursor) {
    }

    public record SearchResponse(List<MovieResult> movies, Pagination pagination) {
    }

    public static final class PersonResolutionException extends RuntimeException {
        private final String role;
        private final String name;
        private final PersonResolver.Resolution resolution;

        public PersonResolutionException(String role, String name, PersonResolver.Resolution resolution) {
            super(resolution.status() == PersonResolver.Status.NOT_FOUND
                    ? "No " + role + " found for '" + name + "'"
                    : "Multiple people match " + role + " '" + name + "'");
            this.role = role;
            this.name = name;
            this.resolution = resolution;
        }

        public String role() {
            return role;
        }

        public String name() {
            return name;
        }

        public PersonResolver.Resolution resolution() {
            return resolution;
        }
    }
}
