package com.moviefinder.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ImdbDatasetCatalogTest {

    @Test
    void catalogContainsTheSixRequiredArchivesAtTheOfficialUrls() {
        assertThat(ImdbDatasetCatalog.datasets())
                .extracting(ImdbDataset::filename)
                .containsExactly(
                        "title.basics.tsv.gz",
                        "title.akas.tsv.gz",
                        "title.principals.tsv.gz",
                        "title.crew.tsv.gz",
                        "name.basics.tsv.gz",
                        "title.ratings.tsv.gz");

        assertThat(ImdbDatasetCatalog.datasets())
                .extracting(dataset -> dataset.url(ImdbDatasetCatalog.BASE_URL).toString())
                .containsExactly(
                        "https://datasets.imdbws.com/title.basics.tsv.gz",
                        "https://datasets.imdbws.com/title.akas.tsv.gz",
                        "https://datasets.imdbws.com/title.principals.tsv.gz",
                        "https://datasets.imdbws.com/title.crew.tsv.gz",
                        "https://datasets.imdbws.com/name.basics.tsv.gz",
                        "https://datasets.imdbws.com/title.ratings.tsv.gz");
    }

    @Test
    void catalogHeadersContainEveryMvpColumn() {
        assertRequiredColumns("title.basics.tsv.gz",
                "tconst", "titleType", "primaryTitle", "originalTitle", "isAdult",
                "startYear", "genres");
        assertRequiredColumns("title.akas.tsv.gz",
                "titleId", "title", "region", "language", "types", "isOriginalTitle");
        assertRequiredColumns("title.principals.tsv.gz", "tconst", "nconst", "category");
        assertRequiredColumns("title.crew.tsv.gz", "tconst", "directors");
        assertRequiredColumns("name.basics.tsv.gz",
                "nconst", "primaryName", "primaryProfession", "knownForTitles");
        assertRequiredColumns("title.ratings.tsv.gz", "tconst", "averageRating", "numVotes");
    }

    private static void assertRequiredColumns(String filename, String... columns) {
        ImdbDataset dataset = ImdbDatasetCatalog.datasets().stream()
                .filter(candidate -> candidate.filename().equals(filename))
                .findFirst()
                .orElseThrow();

        assertThat(dataset.headerColumns()).containsAll(List.of(columns));
    }
}
