package com.moviefinder.ingestion;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImdbIngestionPipelineTest {

    @TempDir
    Path temp;

    @Test
    void joinsTheSixArchivesAndProducesIdenticalRecordsOnRerun() throws IOException {
        Path source = copyFixtures();
        Path destination = temp.resolve("canonical");
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        ImdbIngestionPipeline pipeline = new ImdbIngestionPipeline(
                new PrintStream(log, true, UTF_8), new PrintStream(new ByteArrayOutputStream()));

        ImdbIngestionPipeline.ImportSummary first = pipeline.importAll(source, destination);
        byte[] moviesFirst = Files.readAllBytes(destination.resolve("movies.ndjson"));
        byte[] peopleFirst = Files.readAllBytes(destination.resolve("people.ndjson"));
        assertThat(first.movies()).isEqualTo(2);
        assertThat(first.people()).isEqualTo(4);
        assertThat(first.missingLinkedPeople()).isZero();

        List<String> movies = Files.readAllLines(destination.resolve("movies.ndjson"), UTF_8);
        assertThat(movies).hasSize(2);
        assertThat(movies).anySatisfy(movie -> assertThat(movie)
                .contains("\"id\":\"tt9000000001\"",
                        "\"primaryTitle\":\"Clockwork Harbor\"",
                        "\"releaseYear\":2021",
                        "\"castPersonIds\":[\"nm9000000001\",\"nm9000000002\"]",
                        "\"directorPersonIds\":[\"nm9000000003\"]",
                        "\"genres\":[\"Drama\",\"Mystery\"]",
                        "\"averageRating\":7.4", "\"voteCount\":1842"));
        assertThat(movies).anySatisfy(movie -> assertThat(movie)
                .contains("\"id\":\"tt9000000002\"",
                        "\"castPersonIds\":[\"nm9000000002\"]",
                        "\"directorPersonIds\":[\"nm9000000004\"]"));
        assertThat(Files.readAllLines(destination.resolve("people.ndjson"), UTF_8))
                .anySatisfy(person -> assertThat(person)
                        .contains("\"id\":\"nm9000000002\"",
                                "\"normalizedName\":\"sol rivera\"",
                                "\"knownForTitleIds\":[\"tt9000000001\",\"tt9000000002\"]"));

        ImdbIngestionPipeline.ImportSummary second = pipeline.importAll(source, destination);
        assertThat(second.movies()).isEqualTo(first.movies());
        assertThat(second.people()).isEqualTo(first.people());
        assertThat(Files.readAllBytes(destination.resolve("movies.ndjson"))).isEqualTo(moviesFirst);
        assertThat(Files.readAllBytes(destination.resolve("people.ndjson"))).isEqualTo(peopleFirst);
        assertThat(log.toString(UTF_8)).contains("read=2 accepted=2 rejected=0 filtered=0",
                "Import: movies=2 people=4 missing_linked_people=0 duration_ms=");
    }

    @Test
    void rejectsBadRowsButKeepsValidMovieAndPersonLinks() throws IOException {
        Path source = copyFixtures();
        rewrite(source, "title.basics.tsv.gz",
                "tt9000000003\ttvMovie\tUntimed Movie\t\\N\t0\t\\N\t\\N\t\\N\t\\N",
                "tt9000000004\tmovie\tAdult Movie\tAdult Movie\t1\t2020\t\\N\t90\tDrama",
                "broken\tmovie\tInvalid\tInvalid\t0\t2020\t\\N\t90\tDrama");
        rewrite(source, "title.principals.tsv.gz",
                "tt9000000001\t6\tnm-bad\tactor\t\\N\t\\N",
                "tt9000000001\t7\tnm9000000099\tactor\t\\N\t\\N");
        rewrite(source, "title.ratings.tsv.gz",
                "tt9000000001\tbad\t100",
                "tt9000000001\t7.7\t2000");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        ImdbIngestionPipeline pipeline = new ImdbIngestionPipeline(
                new PrintStream(output, true, UTF_8), new PrintStream(error, true, UTF_8));

        ImdbIngestionPipeline.ImportSummary summary = pipeline.importAll(source, temp.resolve("canonical"));

        assertThat(summary.movies()).isEqualTo(3);
        assertThat(summary.people()).isEqualTo(4);
        assertThat(summary.missingLinkedPeople()).isEqualTo(1);
        assertThat(Files.readAllLines(temp.resolve("canonical/movies.ndjson"), UTF_8))
                .anySatisfy(movie -> assertThat(movie).contains(
                        "\"id\":\"tt9000000003\"", "\"originalTitle\":\"Untimed Movie\"",
                        "\"releaseYear\":null", "\"averageRating\":null", "\"voteCount\":null"))
                .anySatisfy(movie -> assertThat(movie).contains(
                        "\"id\":\"tt9000000001\"", "\"averageRating\":7.7", "\"voteCount\":2000"))
                .allSatisfy(movie -> assertThat(movie).doesNotContain(
                        "\"id\":\"tt9000000004\"", "nm9000000099"));
        assertThat(error.toString(UTF_8)).contains(
                "[invalid] title.basics.tsv.gz:6: invalid title ID",
                "[invalid] title.principals.tsv.gz:7: invalid person ID",
                "[invalid] title.ratings.tsv.gz:4: invalid averageRating");
        assertThat(output.toString(UTF_8)).contains("rejected=1",
                "movies=3 people=4 missing_linked_people=1");
    }

    @Test
    void aBadHeaderDoesNotReplacePreviousOutput() throws IOException {
        Path source = copyFixtures();
        Path destination = temp.resolve("canonical");
        ImdbIngestionPipeline pipeline = new ImdbIngestionPipeline(
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
        pipeline.importAll(source, destination);
        byte[] movies = Files.readAllBytes(destination.resolve("movies.ndjson"));
        byte[] people = Files.readAllBytes(destination.resolve("people.ndjson"));
        rewriteHeader(source.resolve("title.ratings.tsv.gz"), "wrong\theader");

        assertThatThrownBy(() -> pipeline.importAll(source, destination))
                .isInstanceOf(IOException.class).hasMessageContaining("unexpected TSV header");
        assertThat(Files.readAllBytes(destination.resolve("movies.ndjson"))).isEqualTo(movies);
        assertThat(Files.readAllBytes(destination.resolve("people.ndjson"))).isEqualTo(people);
    }

    private Path copyFixtures() throws IOException {
        Path source = Files.createDirectory(temp.resolve("raw"));
        for (ImdbDataset dataset : ImdbDatasetCatalog.datasets()) {
            try (var resource = getClass().getResourceAsStream("/imdb/" + dataset.filename())) {
                assertThat(resource).isNotNull();
                Files.copy(resource, source.resolve(dataset.filename()));
            }
        }
        return source;
    }

    private static void rewrite(Path source, String filename, String... extraRows) throws IOException {
        Path file = source.resolve(filename);
        List<String> rows;
        try (var reader = new java.io.BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(file)), UTF_8))) {
            rows = reader.lines().toList();
        }
        try (var writer = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)), UTF_8)) {
            for (String row : rows) {
                writer.write(row + "\n");
            }
            for (String row : extraRows) {
                writer.write(row + "\n");
            }
        }
    }

    private static void rewriteHeader(Path file, String header) throws IOException {
        List<String> rows;
        try (var reader = new java.io.BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(file)), UTF_8))) {
            rows = reader.lines().toList();
        }
        try (var writer = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)), UTF_8)) {
            writer.write(header + "\n");
            for (String row : rows.subList(1, rows.size())) {
                writer.write(row + "\n");
            }
        }
    }
}
