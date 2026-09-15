package com.moviefinder.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class MoviePersonSearchTest {

    @Test
    void queriesMoviesWithTheResolvedPersonIdAndRequestedRole() throws IOException {
        PersonResolver resolver = new PersonResolver((tier, name) ->
                new PersonResolver.SearchMatches(List.of(
                        new PersonResolver.PersonCandidate("nm0000138", "Leonardo DiCaprio")), 1));
        MoviePersonSearch search = new MoviePersonSearch(resolver, (personId, role, limit) -> {
            assertThat(personId).isEqualTo("nm0000138");
            assertThat(role).isEqualTo(MoviePersonSearch.Role.CAST);
            assertThat(limit).isEqualTo(10);
            return new MoviePersonSearch.MovieMatches(List.of(
                    new MoviePersonSearch.MovieCandidate("tt1375666", "Inception", 2010, 8.8, 2_000_000L)), 1);
        });

        MoviePersonSearch.SearchResult result = search.search("Leonardo DiCaprio",
                MoviePersonSearch.Role.CAST, 10);

        assertThat(result.resolution().person().id()).isEqualTo("nm0000138");
        assertThat(result.movies().candidates()).extracting(MoviePersonSearch.MovieCandidate::id)
                .containsExactly("tt1375666");
    }

    @Test
    void ambiguousAndMissingPeopleNeverQueryMovies() throws IOException {
        AtomicInteger movieCalls = new AtomicInteger();
        MoviePersonSearch.MovieLookup movies = (personId, role, limit) -> {
            movieCalls.incrementAndGet();
            return new MoviePersonSearch.MovieMatches(List.of(), 0);
        };
        PersonResolver ambiguous = new PersonResolver((tier, name) ->
                new PersonResolver.SearchMatches(List.of(
                        new PersonResolver.PersonCandidate("nm1000001", "Alex Carter"),
                        new PersonResolver.PersonCandidate("nm1000002", "Alex Carter")), 2));
        PersonResolver missing = new PersonResolver((tier, name) ->
                new PersonResolver.SearchMatches(List.of(), 0));

        assertThat(new MoviePersonSearch(ambiguous, movies)
                .search("Alex Carter", MoviePersonSearch.Role.DIRECTOR, 10)
                .resolution().status()).isEqualTo(PersonResolver.Status.AMBIGUOUS);
        assertThat(new MoviePersonSearch(missing, movies)
                .search("Nobody", MoviePersonSearch.Role.CAST, 10)
                .resolution().status()).isEqualTo(PersonResolver.Status.NOT_FOUND);
        assertThat(movieCalls).hasValue(0);
        assertThatThrownBy(() -> new MoviePersonSearch(missing, movies)
                .search("Nobody", MoviePersonSearch.Role.CAST, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
