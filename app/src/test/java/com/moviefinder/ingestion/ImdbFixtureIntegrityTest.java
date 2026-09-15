package com.moviefinder.ingestion;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;

class ImdbFixtureIntegrityTest {

    @Test
    void everyFixtureHasTheOfficialHeaderAndConsistentSyntheticIdentifiers() throws IOException {
        Map<String, List<Map<String, String>>> fixtures = new LinkedHashMap<>();
        for (ImdbDataset dataset : ImdbDatasetCatalog.datasets()) {
            fixtures.put(dataset.filename(), readFixture(dataset));
        }

        Set<String> titleIds = values(fixtures.get("title.basics.tsv.gz"), "tconst");
        Set<String> nameIds = values(fixtures.get("name.basics.tsv.gz"), "nconst");

        assertThat(titleIds).containsExactly("tt9000000001", "tt9000000002");
        assertThat(nameIds).containsExactly(
                "nm9000000001", "nm9000000002", "nm9000000003", "nm9000000004");
        assertThat(values(fixtures.get("title.ratings.tsv.gz"), "tconst")).isEqualTo(titleIds);
        assertThat(values(fixtures.get("title.crew.tsv.gz"), "tconst")).isEqualTo(titleIds);
        assertThat(values(fixtures.get("title.akas.tsv.gz"), "titleId")).isSubsetOf(titleIds);
        assertThat(values(fixtures.get("title.principals.tsv.gz"), "tconst")).isSubsetOf(titleIds);
        assertThat(values(fixtures.get("title.principals.tsv.gz"), "nconst")).isSubsetOf(nameIds);
        assertThat(commaSeparatedValues(fixtures.get("title.crew.tsv.gz"), "directors"))
                .isSubsetOf(nameIds);
        assertThat(commaSeparatedValues(fixtures.get("name.basics.tsv.gz"), "knownForTitles"))
                .isSubsetOf(titleIds);

        assertThat(values(fixtures.get("title.basics.tsv.gz"), "primaryTitle"))
                .containsExactly("Clockwork Harbor", "The Lantern Orbit");
        assertThat(values(fixtures.get("name.basics.tsv.gz"), "primaryName"))
                .containsExactly("Avery Quill", "Sol Rivera", "Rowan Pike", "Imani Frost");
    }

    private static List<Map<String, String>> readFixture(ImdbDataset dataset) throws IOException {
        String resourceName = "/imdb/" + dataset.filename();
        InputStream resource = ImdbFixtureIntegrityTest.class.getResourceAsStream(resourceName);
        assertThat(resource).as(resourceName).isNotNull();

        try (resource;
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        new GZIPInputStream(resource), UTF_8))) {
            String header = reader.readLine();
            assertThat(header).isEqualTo(dataset.expectedHeader());
            String[] columns = header.split("\\t", -1);
            List<Map<String, String>> rows = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = line.split("\\t", -1);
                assertThat(values).as(dataset.filename() + " row width").hasSameSizeAs(columns);
                Map<String, String> row = new LinkedHashMap<>();
                for (int index = 0; index < columns.length; index++) {
                    row.put(columns[index], values[index]);
                }
                rows.add(row);
            }
            assertThat(rows).as(dataset.filename()).isNotEmpty();
            return rows;
        }
    }

    private static Set<String> values(List<Map<String, String>> rows, String column) {
        Set<String> values = new LinkedHashSet<>();
        rows.forEach(row -> values.add(row.get(column)));
        return values;
    }

    private static Set<String> commaSeparatedValues(List<Map<String, String>> rows, String column) {
        Set<String> values = new LinkedHashSet<>();
        rows.stream()
                .map(row -> row.get(column))
                .filter(value -> !"\\N".equals(value))
                .flatMap(value -> Arrays.stream(value.split(",")))
                .forEach(values::add);
        return values;
    }
}
