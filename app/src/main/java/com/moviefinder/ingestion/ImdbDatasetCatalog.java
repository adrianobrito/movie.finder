package com.moviefinder.ingestion;

import java.net.URI;
import java.util.List;

/** The IMDb archives and schemas required by the Movie Finder MVP. */
public final class ImdbDatasetCatalog {

    public static final URI BASE_URL = URI.create("https://datasets.imdbws.com/");

    private static final List<ImdbDataset> DATASETS = List.of(
            dataset("title.basics.tsv.gz",
                    "tconst", "titleType", "primaryTitle", "originalTitle", "isAdult",
                    "startYear", "endYear", "runtimeMinutes", "genres"),
            dataset("title.akas.tsv.gz",
                    "titleId", "ordering", "title", "region", "language", "types",
                    "attributes", "isOriginalTitle"),
            dataset("title.principals.tsv.gz",
                    "tconst", "ordering", "nconst", "category", "job", "characters"),
            dataset("title.crew.tsv.gz", "tconst", "directors", "writers"),
            dataset("name.basics.tsv.gz",
                    "nconst", "primaryName", "birthYear", "deathYear", "primaryProfession",
                    "knownForTitles"),
            dataset("title.ratings.tsv.gz", "tconst", "averageRating", "numVotes"));

    private ImdbDatasetCatalog() {
    }

    public static List<ImdbDataset> datasets() {
        return DATASETS;
    }

    private static ImdbDataset dataset(String filename, String... headerColumns) {
        return new ImdbDataset(filename, List.of(headerColumns));
    }
}
