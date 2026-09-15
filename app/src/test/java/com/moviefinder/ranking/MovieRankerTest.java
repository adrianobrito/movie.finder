package com.moviefinder.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.moviefinder.ranking.MovieRanker.MovieCandidate;
import com.moviefinder.ranking.MovieRanker.RankedMovie;
import com.moviefinder.ranking.MovieRanker.RankingConfig;
import com.moviefinder.ranking.MovieRanker.ScoreBreakdown;
import com.moviefinder.ranking.MovieRanker.TitleMatch;

class MovieRankerTest {

    private final MovieRanker ranker = new MovieRanker();

    @Test
    void primaryTitleThenExactAliasPrecedeBroaderMatches() {
        MovieCandidate fuzzy = movie("tt3", TitleMatch.OTHER, 0.9, 10.0, 1_000_000L);
        MovieCandidate alias = movie("tt2", TitleMatch.ALIAS_EXACT, 1, 4.0, 1L);
        MovieCandidate primary = movie("tt1", TitleMatch.PRIMARY_EXACT, 1, 4.0, 1L);

        assertThat(ranker.rank(List.of(fuzzy, alias, primary)))
                .extracting(result -> result.candidate().id())
                .containsExactly("tt1", "tt2", "tt3");
    }

    @Test
    void bayesianQualityKeepsTinyVoteCountFromDominating() {
        MovieCandidate tiny = movie("tt1", TitleMatch.OTHER, 0.5, 10.0, 10L);
        MovieCandidate established = movie("tt2", TitleMatch.OTHER, 0.5, 7.5, 10_000L);

        List<RankedMovie> results = ranker.rank(List.of(tiny, established));

        assertThat(results).extracting(result -> result.candidate().id())
                .containsExactly("tt2", "tt1");
        assertThat(results.get(0).breakdown().bayesianRating()).isCloseTo(7.36,
                org.assertj.core.data.Offset.offset(0.01));
        assertThat(results.get(1).breakdown().bayesianRating()).isCloseTo(6.04,
                org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void relevanceAndRequestedPeopleContributeWithinTheSameTitleTier() {
        MovieRanker configured = new MovieRanker(new RankingConfig(40, 25, 0, 0, 6, 1_000, 1_000_000));
        MovieCandidate fewerPeople = new MovieCandidate("tt1", TitleMatch.OTHER, 0.7,
                1, 2, 0, 1, null, null);
        MovieCandidate allPeople = new MovieCandidate("tt2", TitleMatch.OTHER, 0.7,
                2, 2, 1, 1, null, null);
        MovieCandidate lessRelevant = new MovieCandidate("tt3", TitleMatch.OTHER, 0.2,
                2, 2, 1, 1, null, null);

        List<RankedMovie> results = configured.rank(List.of(fewerPeople, lessRelevant, allPeople));

        assertThat(results).extracting(result -> result.candidate().id())
                .containsExactly("tt2", "tt1", "tt3");
        assertThat(results.get(0).breakdown().personMatchRatio()).isEqualTo(1);
        assertThat(results.get(1).breakdown().personMatchRatio()).isEqualTo(1.0 / 3);
    }

    @Test
    void personOnlySearchUsesQualityAndStableIdTies() {
        MovieCandidate lessPopular = new MovieCandidate("tt1", TitleMatch.NONE, 0,
                1, 1, 0, 0, 8.0, 100L);
        MovieCandidate morePopular = new MovieCandidate("tt2", TitleMatch.NONE, 0,
                1, 1, 0, 0, 8.0, 10_000L);
        MovieCandidate tiedLater = new MovieCandidate("tt9", TitleMatch.NONE, 0,
                1, 1, 0, 0, 8.0, 10_000L);

        List<MovieCandidate> candidates = List.of(tiedLater, lessPopular, morePopular);
        List<RankedMovie> first = ranker.rank(candidates);

        assertThat(first).extracting(result -> result.candidate().id())
                .containsExactly("tt2", "tt9", "tt1");
        assertThat(ranker.rank(List.of(morePopular, tiedLater, lessPopular)))
                .isEqualTo(first);
        assertThat(first.get(0).breakdown().textPoints()).isZero();
    }

    @Test
    void missingRatingGetsNoQualityPointsAndBreakdownExplainsTheTotal() {
        MovieCandidate unrated = movie("tt1", TitleMatch.OTHER, 0.5, null, null);

        ScoreBreakdown breakdown = ranker.rank(List.of(unrated)).getFirst().breakdown();

        assertThat(breakdown.bayesianRating()).isNull();
        assertThat(breakdown.ratingPoints()).isZero();
        assertThat(breakdown.popularityPoints()).isZero();
        assertThat(breakdown.total()).isEqualTo(breakdown.textPoints()
                + breakdown.personPoints() + breakdown.ratingPoints() + breakdown.popularityPoints());
        assertThat(breakdown.titleMatch()).isEqualTo(TitleMatch.OTHER);
    }

    @Test
    void validatesSignalsConfigurationAndDuplicateIds() {
        assertThatThrownBy(() -> movie("bad", TitleMatch.OTHER, 0.5, 8.0, 100L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> movie("tt1", TitleMatch.PRIMARY_EXACT, 0.5, 8.0, 100L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> movie("tt1", TitleMatch.OTHER, Double.NaN, 8.0, 100L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MovieCandidate("tt1", TitleMatch.NONE, 0,
                2, 1, 0, 0, 8.0, 100L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RankingConfig(0, 0, 0, 0, 6, 1_000, 1_000_000))
                .isInstanceOf(IllegalArgumentException.class);
        MovieCandidate candidate = movie("tt1", TitleMatch.OTHER, 0.5, 8.0, 100L);
        assertThatThrownBy(() -> ranker.rank(List.of(candidate, candidate)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MovieCandidate movie(String id, TitleMatch match, double relevance,
            Double rating, Long votes) {
        return new MovieCandidate(id, match, relevance, 0, 0, 0, 0, rating, votes);
    }
}
