package com.moviefinder.ingestion;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/** Streams the six IMDb archives into stable, index-ready movie and person NDJSON files. */
public final class ImdbIngestionPipeline {

    private static final Path DEFAULT_SOURCE = Path.of("data", "imdb", "raw");
    private static final Path DEFAULT_DESTINATION = Path.of("data", "imdb", "canonical");
    private static final int BUCKET_COUNT = 128;
    private static final String MISSING = "\\N";

    private final PrintStream output;
    private final PrintStream error;
    private final Map<String, DatasetMetrics> metrics = new TreeMap<>();
    private final Set<String> selectedTitles = new HashSet<>();
    private final Set<String> linkedPeople = new HashSet<>();

    public ImdbIngestionPipeline(PrintStream output, PrintStream error) {
        this.output = Objects.requireNonNull(output, "output");
        this.error = Objects.requireNonNull(error, "error");
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, PrintStream output, PrintStream error) {
        Path source = DEFAULT_SOURCE;
        Path destination = DEFAULT_DESTINATION;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--source" -> {
                        if (++i == args.length || args[i].isBlank()) {
                            throw new IllegalArgumentException("--source requires a path");
                        }
                        source = Path.of(args[i]);
                    }
                    case "--destination" -> {
                        if (++i == args.length || args[i].isBlank()) {
                            throw new IllegalArgumentException("--destination requires a path");
                        }
                        destination = Path.of(args[i]);
                    }
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
        } catch (IllegalArgumentException exception) {
            error.println("Error: " + exception.getMessage());
            error.println("Usage: ImdbIngestionPipeline [--source <path>] [--destination <path>]");
            return 2;
        }
        try {
            new ImdbIngestionPipeline(output, error).importAll(source, destination);
            return 0;
        } catch (IOException exception) {
            error.println("Import failed: " + exception.getMessage());
            return 1;
        }
    }

    public ImportSummary importAll(Path source, Path destination) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        metrics.clear();
        selectedTitles.clear();
        linkedPeople.clear();
        long started = System.nanoTime();
        for (ImdbDataset dataset : ImdbDatasetCatalog.datasets()) {
            if (!Files.isRegularFile(source.resolve(dataset.filename()))) {
                throw new IOException("missing archive: " + source.resolve(dataset.filename()));
            }
            metrics.put(dataset.filename(), new DatasetMetrics());
        }
        Files.createDirectories(destination);
        Path stage = Files.createTempDirectory(destination, ".imdb-import-");
        Path moviesPartial = stage.resolve("movies.ndjson");
        Path peoplePartial = stage.resolve("people.ndjson");
        try {
            importBasics(source, stage);
            importAliases(source, stage);
            importPrincipals(source, stage);
            importCrew(source, stage);
            importRatings(source, stage);
            importNames(source, stage);
            int missingPeople = linkedPeople.size();
            selectedTitles.clear();

            long people = writePeople(stage, peoplePartial);
            long movies = writeMovies(stage, moviesPartial, linkedPeople);
            linkedPeople.clear();
            replaceAtomically(moviesPartial, destination.resolve("movies.ndjson"));
            replaceAtomically(peoplePartial, destination.resolve("people.ndjson"));
            long durationMillis = (System.nanoTime() - started) / 1_000_000;
            metrics.forEach((name, metric) -> output.printf(
                    "%s: read=%d accepted=%d rejected=%d filtered=%d%n",
                    name, metric.read, metric.accepted, metric.rejected, metric.filtered));
            output.printf("Import: movies=%d people=%d missing_linked_people=%d duration_ms=%d%n",
                    movies, people, missingPeople, durationMillis);
            return new ImportSummary(movies, people, missingPeople, durationMillis);
        } finally {
            Files.deleteIfExists(moviesPartial);
            Files.deleteIfExists(peoplePartial);
            deleteStage(stage);
        }
    }

    private void importBasics(Path source, Path stage) throws IOException {
        try (BucketWriters buckets = new BucketWriters(stage, "basics")) {
            readArchive(source, "title.basics.tsv.gz", row -> {
                String id = titleId(row[0]);
                if (!"movie".equals(row[1]) && !"tvMovie".equals(row[1])) {
                    filtered("title.basics.tsv.gz");
                    return;
                }
                if (!"0".equals(row[4])) {
                    if ("1".equals(row[4])) {
                        filtered("title.basics.tsv.gz");
                        return;
                    }
                    throw new IllegalArgumentException("invalid isAdult value");
                }
                String primary = required(row[2], "primaryTitle");
                String original = optional(row[3]);
                Integer year = optionalYear(row[5]);
                TreeSet<String> genres = csvValues(row[8]);
                if (!selectedTitles.add(id)) {
                    throw new IllegalArgumentException("duplicate title ID " + id);
                }
                buckets.write(id, id + "\t" + primary + "\t" + nullToEmpty(original)
                        + "\t" + (year == null ? "" : year) + "\t" + String.join(",", genres));
                accepted("title.basics.tsv.gz");
            });
        }
    }

    private void importAliases(Path source, Path stage) throws IOException {
        try (BucketWriters buckets = new BucketWriters(stage, "aliases")) {
            readArchive(source, "title.akas.tsv.gz", row -> {
                String id = titleId(row[0]);
                if (!selectedTitles.contains(id)) {
                    filtered("title.akas.tsv.gz");
                    return;
                }
                String alias = required(row[2], "title");
                buckets.write(id, id + "\t" + alias);
                accepted("title.akas.tsv.gz");
            });
        }
    }

    private void importPrincipals(Path source, Path stage) throws IOException {
        try (BucketWriters buckets = new BucketWriters(stage, "links")) {
            readArchive(source, "title.principals.tsv.gz", row -> {
                String id = titleId(row[0]);
                if (!selectedTitles.contains(id)) {
                    filtered("title.principals.tsv.gz");
                    return;
                }
                String kind = switch (row[3]) {
                    case "actor", "actress" -> "cast";
                    case "director" -> "director";
                    default -> null;
                };
                if (kind == null) {
                    filtered("title.principals.tsv.gz");
                    return;
                }
                String person = personId(row[2]);
                buckets.write(id, id + "\t" + kind + "\t" + person);
                linkedPeople.add(person);
                accepted("title.principals.tsv.gz");
            });
        }
    }

    private void importCrew(Path source, Path stage) throws IOException {
        try (BucketWriters buckets = new BucketWriters(stage, "links", true)) {
            readArchive(source, "title.crew.tsv.gz", row -> {
                String id = titleId(row[0]);
                if (!selectedTitles.contains(id)) {
                    filtered("title.crew.tsv.gz");
                    return;
                }
                TreeSet<String> directors = csvValues(row[1]);
                if (directors.isEmpty()) {
                    filtered("title.crew.tsv.gz");
                    return;
                }
                for (String person : directors) {
                    personId(person);
                }
                for (String person : directors) {
                    buckets.write(id, id + "\tdirector\t" + person);
                    linkedPeople.add(person);
                }
                accepted("title.crew.tsv.gz");
            });
        }
    }

    private void importRatings(Path source, Path stage) throws IOException {
        try (BucketWriters buckets = new BucketWriters(stage, "ratings")) {
            readArchive(source, "title.ratings.tsv.gz", row -> {
                String id = titleId(row[0]);
                if (!selectedTitles.contains(id)) {
                    filtered("title.ratings.tsv.gz");
                    return;
                }
                BigDecimal rating = rating(row[1]);
                long votes = votes(row[2]);
                buckets.write(id, id + "\t" + rating.toPlainString() + "\t" + votes);
                accepted("title.ratings.tsv.gz");
            });
        }
    }

    private void importNames(Path source, Path stage) throws IOException {
        try (BucketWriters buckets = new BucketWriters(stage, "people")) {
            readArchive(source, "name.basics.tsv.gz", row -> {
                String id = personId(row[0]);
                if (!linkedPeople.contains(id)) {
                    filtered("name.basics.tsv.gz");
                    return;
                }
                String name = required(row[1], "primaryName");
                TreeSet<String> professions = csvValues(row[4]);
                TreeSet<String> knownFor = csvValues(row[5]);
                knownFor.removeIf(title -> !selectedTitles.contains(title));
                buckets.write(id, id + "\t" + name + "\t" + String.join(",", professions)
                        + "\t" + String.join(",", knownFor));
                linkedPeople.remove(id);
                accepted("name.basics.tsv.gz");
            });
        }
    }

    private long writeMovies(Path stage, Path partial, Set<String> missingPeople) throws IOException {
        long count = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(partial, UTF_8)) {
            for (int bucket = 0; bucket < BUCKET_COUNT; bucket++) {
                TreeMap<String, Movie> movies = new TreeMap<>();
                readBucket(stage, "basics", bucket, row -> {
                    Movie movie = new Movie(row[0], row[1], row[2].isEmpty() ? row[1] : row[2]);
                    if (!row[3].isEmpty()) {
                        movie.year = Integer.parseInt(row[3]);
                    }
                    movie.genres.addAll(csvValues(row[4]));
                    movies.put(movie.id, movie);
                });
                readBucket(stage, "aliases", bucket, row -> {
                    Movie movie = movies.get(row[0]);
                    if (movie != null && !row[1].equals(movie.primaryTitle)
                            && !row[1].equals(movie.originalTitle)) {
                        movie.aliases.add(row[1]);
                    }
                });
                readBucket(stage, "links", bucket, row -> {
                    Movie movie = movies.get(row[0]);
                    if (movie != null) {
                        ("cast".equals(row[1]) ? movie.cast : movie.directors).add(row[2]);
                    }
                });
                readBucket(stage, "ratings", bucket, row -> {
                    Movie movie = movies.get(row[0]);
                    if (movie != null) {
                        BigDecimal candidate = new BigDecimal(row[1]);
                        long candidateVotes = Long.parseLong(row[2]);
                        if (movie.rating == null || candidateVotes > movie.voteCount
                                || candidateVotes == movie.voteCount && candidate.compareTo(movie.rating) > 0) {
                            movie.rating = candidate;
                            movie.voteCount = candidateVotes;
                        }
                    }
                });
                for (Movie movie : movies.values()) {
                    movie.cast.removeAll(missingPeople);
                    movie.directors.removeAll(missingPeople);
                    writer.write(movie.toJson());
                    writer.newLine();
                    count++;
                }
            }
        }
        return count;
    }

    private long writePeople(Path stage, Path partial) throws IOException {
        long count = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(partial, UTF_8)) {
            for (int bucket = 0; bucket < BUCKET_COUNT; bucket++) {
                TreeMap<String, Person> people = new TreeMap<>();
                readBucket(stage, "people", bucket, row -> {
                    Person person = people.computeIfAbsent(row[0], Person::new);
                    if (person.name == null || row[1].compareTo(person.name) < 0) {
                        person.name = row[1];
                    }
                    person.professions.addAll(csvValues(row[2]));
                    person.knownFor.addAll(csvValues(row[3]));
                });
                for (Person person : people.values()) {
                    writer.write(person.toJson());
                    writer.newLine();
                    count++;
                }
            }
        }
        return count;
    }

    private void readArchive(Path source, String filename, Consumer<String[]> handler) throws IOException {
        ImdbDataset dataset = ImdbDatasetCatalog.datasets().stream()
                .filter(candidate -> candidate.filename().equals(filename)).findFirst().orElseThrow();
        DatasetMetrics metric = metrics.get(filename);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(source.resolve(filename))), UTF_8))) {
            if (!dataset.expectedHeader().equals(reader.readLine())) {
                throw new IOException("unexpected TSV header in " + filename);
            }
            String line;
            long lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                metric.read++;
                String[] row = line.split("\t", -1);
                if (row.length != dataset.headerColumns().size()) {
                    reject(filename, lineNumber, "expected " + dataset.headerColumns().size()
                            + " columns, got " + row.length);
                    continue;
                }
                try {
                    handler.accept(row);
                } catch (IllegalArgumentException exception) {
                    reject(filename, lineNumber, exception.getMessage());
                } catch (UncheckedImportIOException exception) {
                    throw exception.getCause();
                }
            }
        }
    }

    private void reject(String filename, long lineNumber, String reason) {
        metrics.get(filename).rejected++;
        error.printf("[invalid] %s:%d: %s%n", filename, lineNumber, reason);
    }

    private void accepted(String filename) {
        metrics.get(filename).accepted++;
    }

    private void filtered(String filename) {
        metrics.get(filename).filtered++;
    }

    private static String titleId(String value) {
        if (!validId(value, 't')) {
            throw new IllegalArgumentException("invalid title ID: " + value);
        }
        return value;
    }

    private static String personId(String value) {
        if (!validId(value, 'n')) {
            throw new IllegalArgumentException("invalid person ID: " + value);
        }
        return value;
    }

    private static boolean validId(String value, char prefix) {
        if (value.length() < 3 || value.charAt(0) != prefix
                || value.charAt(1) != (prefix == 't' ? 't' : 'm')) {
            return false;
        }
        for (int i = 2; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static String required(String value, String column) {
        String normalized = optional(value);
        if (normalized == null) {
            throw new IllegalArgumentException("missing " + column);
        }
        return normalized;
    }

    private static String optional(String value) {
        String stripped = value.strip();
        return stripped.isEmpty() || MISSING.equals(stripped) ? null : stripped;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Integer optionalYear(String value) {
        String normalized = optional(value);
        if (normalized == null) {
            return null;
        }
        try {
            int year = Integer.parseInt(normalized);
            if (year < 1800 || year > 9999) {
                throw new NumberFormatException();
            }
            return year;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid startYear: " + value);
        }
    }

    private static BigDecimal rating(String value) {
        try {
            BigDecimal result = new BigDecimal(value).stripTrailingZeros();
            if (result.compareTo(BigDecimal.ZERO) < 0 || result.compareTo(BigDecimal.TEN) > 0) {
                throw new NumberFormatException();
            }
            return result;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid averageRating: " + value);
        }
    }

    private static long votes(String value) {
        try {
            long result = Long.parseLong(value);
            if (result < 0) {
                throw new NumberFormatException();
            }
            return result;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid numVotes: " + value);
        }
    }

    private static TreeSet<String> csvValues(String value) {
        TreeSet<String> result = new TreeSet<>();
        String normalized = optional(value);
        if (normalized != null) {
            for (String token : normalized.split(",", -1)) {
                String item = optional(token);
                if (item != null) {
                    result.add(item);
                }
            }
        }
        return result;
    }

    private static void readBucket(Path stage, String category, int bucket, Consumer<String[]> handler)
            throws IOException {
        Path file = stage.resolve(category).resolve(bucketName(bucket));
        if (!Files.exists(file)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                handler.accept(line.split("\t", -1));
            }
        }
    }

    private static int bucketFor(String id) {
        return id.hashCode() & (BUCKET_COUNT - 1);
    }

    private static String bucketName(int bucket) {
        return String.format(Locale.ROOT, "%03d.tsv", bucket);
    }

    private static void replaceAtomically(Path partial, Path target) throws IOException {
        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("destination does not support atomic replacement: " + target, exception);
        }
    }

    private static void deleteStage(Path stage) throws IOException {
        try (var files = Files.walk(stage)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void jsonString(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        json.append('"');
    }

    private static void jsonArray(StringBuilder json, Set<String> values) {
        json.append('[');
        boolean first = true;
        for (String value : values) {
            if (!first) {
                json.append(',');
            }
            jsonString(json, value);
            first = false;
        }
        json.append(']');
    }

    private static String normalizedName(String name) {
        String decomposed = Normalizer.normalize(name, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    public record ImportSummary(long movies, long people, int missingLinkedPeople, long durationMillis) {
    }

    private static final class DatasetMetrics {
        long read;
        long accepted;
        long rejected;
        long filtered;
    }

    private static final class Movie {
        final String id;
        final String primaryTitle;
        final String originalTitle;
        final TreeSet<String> aliases = new TreeSet<>();
        final TreeSet<String> cast = new TreeSet<>();
        final TreeSet<String> directors = new TreeSet<>();
        final TreeSet<String> genres = new TreeSet<>();
        Integer year;
        BigDecimal rating;
        long voteCount;

        Movie(String id, String primaryTitle, String originalTitle) {
            this.id = id;
            this.primaryTitle = primaryTitle;
            this.originalTitle = originalTitle;
        }

        String toJson() {
            StringBuilder json = new StringBuilder("{\"id\":");
            jsonString(json, id);
            json.append(",\"primaryTitle\":");
            jsonString(json, primaryTitle);
            json.append(",\"originalTitle\":");
            jsonString(json, originalTitle);
            json.append(",\"aliases\":");
            jsonArray(json, aliases);
            json.append(",\"releaseYear\":").append(year == null ? "null" : year);
            json.append(",\"castPersonIds\":");
            jsonArray(json, cast);
            json.append(",\"directorPersonIds\":");
            jsonArray(json, directors);
            json.append(",\"genres\":");
            jsonArray(json, genres);
            json.append(",\"averageRating\":")
                    .append(rating == null ? "null" : rating.toPlainString());
            json.append(",\"voteCount\":").append(rating == null ? "null" : voteCount);
            return json.append('}').toString();
        }
    }

    private static final class Person {
        final String id;
        final TreeSet<String> professions = new TreeSet<>();
        final TreeSet<String> knownFor = new TreeSet<>();
        String name;

        Person(String id) {
            this.id = id;
        }

        String toJson() {
            StringBuilder json = new StringBuilder("{\"id\":");
            jsonString(json, id);
            json.append(",\"primaryName\":");
            jsonString(json, name);
            json.append(",\"normalizedName\":");
            jsonString(json, normalizedName(name));
            json.append(",\"professions\":");
            jsonArray(json, professions);
            json.append(",\"knownForTitleIds\":");
            jsonArray(json, knownFor);
            return json.append('}').toString();
        }
    }

    private static final class BucketWriters implements AutoCloseable {
        private final Path directory;
        private final boolean append;
        private final BufferedWriter[] writers = new BufferedWriter[BUCKET_COUNT];

        BucketWriters(Path stage, String category) throws IOException {
            this(stage, category, false);
        }

        BucketWriters(Path stage, String category, boolean append) throws IOException {
            directory = stage.resolve(category);
            this.append = append;
            Files.createDirectories(directory);
        }

        void write(String id, String line) {
            int bucket = bucketFor(id);
            try {
                if (writers[bucket] == null) {
                    Path path = directory.resolve(bucketName(bucket));
                    writers[bucket] = append
                            ? Files.newBufferedWriter(path, UTF_8, java.nio.file.StandardOpenOption.CREATE,
                                    java.nio.file.StandardOpenOption.APPEND)
                            : Files.newBufferedWriter(path, UTF_8);
                }
                writers[bucket].write(line);
                writers[bucket].newLine();
            } catch (IOException exception) {
                throw new UncheckedImportIOException(exception);
            }
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (BufferedWriter writer : writers) {
                if (writer != null) {
                    try {
                        writer.close();
                    } catch (IOException exception) {
                        if (failure == null) {
                            failure = exception;
                        } else {
                            failure.addSuppressed(exception);
                        }
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class UncheckedImportIOException extends RuntimeException {
        UncheckedImportIOException(IOException cause) {
            super(cause);
        }

        @Override
        public IOException getCause() {
            return (IOException) super.getCause();
        }
    }
}
