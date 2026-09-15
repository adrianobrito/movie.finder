package com.moviefinder.ingestion;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

/** Downloads and validates the IMDb archives used by the MVP without starting Spring. */
public final class ImdbDatasetDownloader {

    private static final Path DEFAULT_DESTINATION = Path.of("data", "imdb", "raw");
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);

    private final HttpClient httpClient;
    private final URI baseUrl;
    private final List<ImdbDataset> datasets;

    public ImdbDatasetDownloader(HttpClient httpClient, URI baseUrl) {
        this(httpClient, baseUrl, ImdbDatasetCatalog.datasets());
    }

    ImdbDatasetDownloader(HttpClient httpClient, URI baseUrl, List<ImdbDataset> datasets) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.datasets = List.copyOf(datasets);
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, PrintStream output, PrintStream error) {
        DownloadOptions options;
        try {
            options = DownloadOptions.parse(args);
        } catch (IllegalArgumentException exception) {
            error.println("Error: " + exception.getMessage());
            error.println("Usage: ImdbDatasetDownloader [--destination <path>] [--refresh]");
            return 2;
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        DownloadSummary summary = new ImdbDatasetDownloader(client, ImdbDatasetCatalog.BASE_URL)
                .downloadAll(options.destination(), options.refresh(), output);
        return summary.failed() == 0 ? 0 : 1;
    }

    public DownloadSummary downloadAll(Path destination, boolean refresh, PrintStream output) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(output, "output");

        int downloaded = 0;
        int skipped = 0;
        int failed = 0;

        try {
            Files.createDirectories(destination);
        } catch (IOException exception) {
            for (ImdbDataset dataset : datasets) {
                output.printf("[failed] %s: cannot create destination: %s%n",
                        dataset.filename(), exception.getMessage());
            }
            DownloadSummary summary = new DownloadSummary(0, 0, datasets.size());
            printSummary(output, summary);
            return summary;
        }

        for (ImdbDataset dataset : datasets) {
            try {
                DownloadOutcome outcome = download(dataset, destination, refresh, output);
                if (outcome == DownloadOutcome.DOWNLOADED) {
                    downloaded++;
                } else {
                    skipped++;
                }
            } catch (IOException exception) {
                failed++;
                output.printf("[failed] %s: %s%n", dataset.filename(), exception.getMessage());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                failed++;
                output.printf("[failed] %s: download interrupted%n", dataset.filename());
            }
        }

        DownloadSummary summary = new DownloadSummary(downloaded, skipped, failed);
        printSummary(output, summary);
        return summary;
    }

    private DownloadOutcome download(
            ImdbDataset dataset,
            Path destination,
            boolean refresh,
            PrintStream output) throws IOException, InterruptedException {
        Path target = destination.resolve(dataset.filename());
        Path partial = destination.resolve(dataset.filename() + ".part");

        if (Files.isRegularFile(target) && !refresh) {
            try {
                validate(target, dataset);
                output.printf("[skipped] %s (existing file is valid)%n", dataset.filename());
                return DownloadOutcome.SKIPPED;
            } catch (IOException exception) {
                output.printf("[replace] %s (existing file is invalid: %s)%n",
                        dataset.filename(), exception.getMessage());
            }
        } else {
            output.printf("[download] %s%n", dataset.filename());
        }

        Files.deleteIfExists(partial);
        try {
            HttpRequest request = HttpRequest.newBuilder(dataset.url(baseUrl))
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", "movie-finder-dataset-downloader/0.1")
                    .GET()
                    .build();
            HttpResponse<Path> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofFile(
                            partial,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE));

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP " + response.statusCode() + " from " + dataset.url(baseUrl));
            }

            validate(partial, dataset);
            moveAtomically(partial, target);
            output.printf("[downloaded] %s (%d bytes)%n", dataset.filename(), Files.size(target));
            return DownloadOutcome.DOWNLOADED;
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    static void validate(Path archive, ImdbDataset dataset) throws IOException {
        if (!Files.isRegularFile(archive) || Files.size(archive) == 0) {
            throw new IOException("archive is empty or missing");
        }

        String header;
        try (InputStream input = Files.newInputStream(archive);
                GZIPInputStream gzip = new GZIPInputStream(input);
                BufferedReader reader = new BufferedReader(new InputStreamReader(gzip, UTF_8))) {
            header = reader.readLine();
            while (reader.readLine() != null) {
                // Consume the complete stream so gzip checksum/trailer errors are detected.
            }
        } catch (IOException exception) {
            throw new IOException("invalid gzip archive", exception);
        }

        if (!dataset.expectedHeader().equals(header)) {
            throw new IOException("unexpected TSV header");
        }
    }

    private static void moveAtomically(Path partial, Path target) throws IOException {
        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("destination does not support atomic replacement", exception);
        }
    }

    private static void printSummary(PrintStream output, DownloadSummary summary) {
        output.printf("Summary: downloaded=%d skipped=%d failed=%d%n",
                summary.downloaded(), summary.skipped(), summary.failed());
    }

    public record DownloadSummary(int downloaded, int skipped, int failed) {
    }

    private enum DownloadOutcome {
        DOWNLOADED,
        SKIPPED
    }

    private record DownloadOptions(Path destination, boolean refresh) {

        private static DownloadOptions parse(String[] args) {
            Path destination = DEFAULT_DESTINATION;
            boolean refresh = false;

            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--destination" -> {
                        if (++index >= args.length || args[index].isBlank()) {
                            throw new IllegalArgumentException("--destination requires a path");
                        }
                        destination = Path.of(args[index]);
                    }
                    case "--refresh" -> refresh = true;
                    default -> throw new IllegalArgumentException("unknown argument: " + args[index]);
                }
            }
            return new DownloadOptions(destination, refresh);
        }
    }
}
