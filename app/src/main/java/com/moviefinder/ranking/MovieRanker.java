package com.moviefinder.ranking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Scores a supplied set of movie candidates without depending on a search backend. */
public final class MovieRanker {

    private final RankingConfig config;

    public MovieRanker() {
        this(RankingConfig.defaults());
    }

    public MovieRanker(RankingConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public RankingConfig config() {
        return config;
    }

    /** Ranking applies only to the supplied candidates; retrieval must select them separately. */
    public List<RankedMovie> rank(List<MovieCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        List<RankedMovie> ranked = new ArrayList<>(candidates.size());
        Set<String> ids = new HashSet<>();
        for (MovieCandidate candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate");
            if (!ids.add(candidate.id())) {
                throw new IllegalArgumentException("duplicate movie ID: " + candidate.id());
            }
            ranked.add(new RankedMovie(candidate, score(candidate)));
        }
        ranked.sort(Comparator
                .comparingInt((RankedMovie movie) -> movie.candidate().titleMatch().sortPriority())
                .thenComparing((left, right) -> Double.compare(
                        right.breakdown().total(), left.breakdown().total()))
                .thenComparing(movie -> movie.candidate().id()));
        return List.copyOf(ranked);
    }

    private ScoreBreakdown score(MovieCandidate candidate) {
        double textRelevance = candidate.titleRelevance();
        long requestedPeople = (long) candidate.requestedActors() + candidate.requestedDirectors();
        long matchedPeople = (long) candidate.matchedActors() + candidate.matchedDirectors();
        double personMatchRatio = requestedPeople == 0 ? 0.0 : (double) matchedPeople / requestedPeople;

        Double bayesianRating = null;
        double ratingQuality = 0.0;
        if (candidate.averageRating() != null && candidate.voteCount() != null) {
            double votes = candidate.voteCount();
            double denominator = votes + config.priorVotes();
            bayesianRating = votes / denominator * candidate.averageRating()
                    + config.priorVotes() / denominator * config.priorRating();
            ratingQuality = bayesianRating / 10.0;
        }
        long votes = candidate.voteCount() == null ? 0L : candidate.voteCount();
        double popularity = Math.log1p(Math.min(votes, config.popularityVoteCap()))
                / Math.log1p(config.popularityVoteCap());

        double textPoints = config.textWeight() * textRelevance;
        double personPoints = config.personWeight() * personMatchRatio;
        double ratingPoints = config.ratingWeight() * ratingQuality;
        double popularityPoints = config.popularityWeight() * popularity;
        double total = textPoints + personPoints + ratingPoints + popularityPoints;
        return new ScoreBreakdown(candidate.titleMatch(), textRelevance, personMatchRatio,
                bayesianRating, popularity, textPoints, personPoints, ratingPoints,
                popularityPoints, total);
    }

    /** A title query's primary exact match precedes alias exact and broader matches. */
    public enum TitleMatch {
        PRIMARY_EXACT(0), ALIAS_EXACT(1), OTHER(2), NONE(3);

        private final int sortPriority;

        TitleMatch(int sortPriority) {
            this.sortPriority = sortPriority;
        }

        int sortPriority() {
            return sortPriority;
        }
    }

    /** Relevance is supplied by search and normalized to [0, 1]. */
    public record MovieCandidate(String id, TitleMatch titleMatch, double titleRelevance,
            int matchedActors, int requestedActors, int matchedDirectors, int requestedDirectors,
            Double averageRating, Long voteCount) {
        public MovieCandidate {
            if (id == null || !id.matches("tt\\d+")) {
                throw new IllegalArgumentException("canonical IMDb movie ID is required");
            }
            Objects.requireNonNull(titleMatch, "titleMatch");
            if (!Double.isFinite(titleRelevance) || titleRelevance < 0 || titleRelevance > 1) {
                throw new IllegalArgumentException("title relevance must be between 0 and 1");
            }
            if ((titleMatch == TitleMatch.NONE && titleRelevance != 0)
                    || ((titleMatch == TitleMatch.PRIMARY_EXACT || titleMatch == TitleMatch.ALIAS_EXACT)
                    && titleRelevance != 1)) {
                throw new IllegalArgumentException("exact title matches require relevance 1; no title match requires 0");
            }
            if (matchedActors < 0 || requestedActors < matchedActors
                    || matchedDirectors < 0 || requestedDirectors < matchedDirectors) {
                throw new IllegalArgumentException("invalid requested person match counts");
            }
            if (averageRating != null && (!Double.isFinite(averageRating)
                    || averageRating < 0 || averageRating > 10)) {
                throw new IllegalArgumentException("IMDb rating must be between 0 and 10");
            }
            if (voteCount != null && voteCount < 0) {
                throw new IllegalArgumentException("vote count must not be negative");
            }
        }
    }

    /** Weights and priors are explicit so callers can test or tune them. */
    public record RankingConfig(double textWeight, double personWeight, double ratingWeight,
            double popularityWeight, double priorRating, double priorVotes,
            long popularityVoteCap) {
        public RankingConfig {
            if (!nonNegativeFinite(textWeight) || !nonNegativeFinite(personWeight)
                    || !nonNegativeFinite(ratingWeight) || !nonNegativeFinite(popularityWeight)
                    || !Double.isFinite(textWeight + personWeight + ratingWeight + popularityWeight)
                    || textWeight + personWeight + ratingWeight + popularityWeight <= 0) {
                throw new IllegalArgumentException("ranking weights must be finite and include a positive weight");
            }
            if (!Double.isFinite(priorRating) || priorRating < 0 || priorRating > 10
                    || !Double.isFinite(priorVotes) || priorVotes <= 0 || popularityVoteCap <= 0) {
                throw new IllegalArgumentException("invalid rating prior or popularity cap");
            }
        }

        public static RankingConfig defaults() {
            return new RankingConfig(40, 25, 25, 10, 6.0, 1_000, 1_000_000);
        }

        private static boolean nonNegativeFinite(double value) {
            return Double.isFinite(value) && value >= 0;
        }
    }

    /** All point contributions use the same configured scale and sum to total. */
    public record ScoreBreakdown(TitleMatch titleMatch, double textRelevance,
            double personMatchRatio, Double bayesianRating, double popularity,
            double textPoints, double personPoints, double ratingPoints,
            double popularityPoints, double total) {
    }

    public record RankedMovie(MovieCandidate candidate, ScoreBreakdown breakdown) {
    }
}
