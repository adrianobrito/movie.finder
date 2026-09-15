package com.moviefinder.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PersonResolverTest {

    private static final PersonResolver.PersonCandidate LEONARDO =
            new PersonResolver.PersonCandidate("nm0000138", "Leonardo DiCaprio");

    @Test
    void exactMatchWinsWithoutQueryingBroaderTiers() throws IOException {
        List<PersonResolver.MatchTier> queried = new ArrayList<>();
        PersonResolver resolver = new PersonResolver((tier, name) -> {
            queried.add(tier);
            assertThat(name).isEqualTo("LEONARDO DICAPRIO");
            return new PersonResolver.SearchMatches(List.of(LEONARDO), 1);
        });

        PersonResolver.Resolution result = resolver.resolve("  LEONARDO DICAPRIO  ");

        assertThat(result.status()).isEqualTo(PersonResolver.Status.RESOLVED);
        assertThat(result.matchTier()).isEqualTo(PersonResolver.MatchTier.EXACT);
        assertThat(result.person()).isEqualTo(LEONARDO);
        assertThat(queried).containsExactly(PersonResolver.MatchTier.EXACT);
    }

    @Test
    void typoFallsThroughToFuzzyLookup() throws IOException {
        List<PersonResolver.MatchTier> queried = new ArrayList<>();
        PersonResolver resolver = new PersonResolver((tier, name) -> {
            queried.add(tier);
            return tier == PersonResolver.MatchTier.FUZZY
                    ? new PersonResolver.SearchMatches(List.of(LEONARDO), 1)
                    : new PersonResolver.SearchMatches(List.of(), 0);
        });

        PersonResolver.Resolution result = resolver.resolve("Leonrdo DiCaprio");

        assertThat(result.person().id()).isEqualTo("nm0000138");
        assertThat(result.matchTier()).isEqualTo(PersonResolver.MatchTier.FUZZY);
        assertThat(queried).containsExactly(PersonResolver.MatchTier.EXACT,
                PersonResolver.MatchTier.PREFIX, PersonResolver.MatchTier.FUZZY);
    }

    @Test
    void sameNameAndTruncatedCandidatesRemainAmbiguous() throws IOException {
        PersonResolver resolver = new PersonResolver((tier, name) ->
                new PersonResolver.SearchMatches(List.of(
                        new PersonResolver.PersonCandidate("nm1000001", "Alex Carter"),
                        new PersonResolver.PersonCandidate("nm1000002", "Alex Carter")), 3));

        PersonResolver.Resolution result = resolver.resolve("Alex Carter");

        assertThat(result.status()).isEqualTo(PersonResolver.Status.AMBIGUOUS);
        assertThat(result.totalCandidates()).isEqualTo(3);
        assertThat(result.candidates()).hasSize(2);
        assertThatThrownBy(result::person).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingNameReturnsNoCandidate() throws IOException {
        PersonResolver resolver = new PersonResolver((tier, name) ->
                new PersonResolver.SearchMatches(List.of(), 0));

        PersonResolver.Resolution result = resolver.resolve("Nobody Here");

        assertThat(result.status()).isEqualTo(PersonResolver.Status.NOT_FOUND);
        assertThat(result.candidates()).isEmpty();
        assertThatThrownBy(() -> resolver.resolve(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizationMatchesTheIngestedNameFormat() {
        assertThat(PersonResolver.normalizeName("  LÉONARDO   DiCaprio  "))
                .isEqualTo("leonardo dicaprio");
    }
}
