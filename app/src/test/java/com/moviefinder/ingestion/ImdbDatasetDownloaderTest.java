package com.moviefinder.ingestion;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImdbDatasetDownloaderTest {

    private final Map<String, StubResponse> responses = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> requestCounts = new ConcurrentHashMap<>();

    @TempDir
    Path tempDirectory;

    private HttpServer server;
    private ImdbDatasetDownloader downloader;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        URI baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        downloader = new ImdbDatasetDownloader(HttpClient.newHttpClient(), baseUrl);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void downloadsToAConfigurableDestinationThenSkipsValidFiles() throws IOException {
        installValidResponses("first");
        Path destination = tempDirectory.resolve("custom-imdb-location");

        ByteArrayOutputStream firstOutput = new ByteArrayOutputStream();
        ImdbDatasetDownloader.DownloadSummary first = downloader.downloadAll(
                destination, false, new PrintStream(firstOutput, true, UTF_8));

        assertThat(first).isEqualTo(new ImdbDatasetDownloader.DownloadSummary(6, 0, 0));
        assertThat(firstOutput.toString(UTF_8)).contains("Summary: downloaded=6 skipped=0 failed=0");
        assertThat(ImdbDatasetCatalog.datasets())
                .allSatisfy(dataset -> assertThat(destination.resolve(dataset.filename())).isRegularFile());

        ByteArrayOutputStream secondOutput = new ByteArrayOutputStream();
        ImdbDatasetDownloader.DownloadSummary second = downloader.downloadAll(
                destination, false, new PrintStream(secondOutput, true, UTF_8));

        assertThat(second).isEqualTo(new ImdbDatasetDownloader.DownloadSummary(0, 6, 0));
        assertThat(secondOutput.toString(UTF_8)).contains("Summary: downloaded=0 skipped=6 failed=0");
        assertThat(requestCounts.values()).allSatisfy(count -> assertThat(count).hasValue(1));
    }

    @Test
    void forcedRefreshAtomicallyReplacesValidFiles() throws IOException {
        installValidResponses("first");
        Path destination = tempDirectory.resolve("refresh");
        downloader.downloadAll(destination, false, quietOutput());
        Path archive = destination.resolve("title.basics.tsv.gz");
        byte[] original = Files.readAllBytes(archive);

        installValidResponses("second");
        ImdbDatasetDownloader.DownloadSummary summary = downloader.downloadAll(
                destination, true, quietOutput());

        assertThat(summary).isEqualTo(new ImdbDatasetDownloader.DownloadSummary(6, 0, 0));
        assertThat(Files.readAllBytes(archive)).isNotEqualTo(original);
        assertNoPartialFiles(destination);
    }

    @Test
    void refreshFailuresPreserveExistingFilesAndRemovePartialFiles() throws IOException {
        installValidResponses("original");
        Path destination = tempDirectory.resolve("preserved");
        downloader.downloadAll(destination, false, quietOutput());
        Map<String, byte[]> originals = Map.of(
                "title.basics.tsv.gz", Files.readAllBytes(destination.resolve("title.basics.tsv.gz")),
                "title.akas.tsv.gz", Files.readAllBytes(destination.resolve("title.akas.tsv.gz")),
                "title.principals.tsv.gz", Files.readAllBytes(destination.resolve("title.principals.tsv.gz")));

        installValidResponses("replacement");
        responses.put("title.basics.tsv.gz", new StubResponse(503, "unavailable".getBytes(UTF_8)));
        responses.put("title.akas.tsv.gz", new StubResponse(200, "not gzip".getBytes(UTF_8)));
        responses.put("title.principals.tsv.gz", new StubResponse(200, gzip("wrong\theader\n")));

        ImdbDatasetDownloader.DownloadSummary summary = downloader.downloadAll(
                destination, true, quietOutput());

        assertThat(summary).isEqualTo(new ImdbDatasetDownloader.DownloadSummary(3, 0, 3));
        originals.forEach((filename, bytes) ->
                assertThat(readBytes(destination.resolve(filename))).isEqualTo(bytes));
        assertNoPartialFiles(destination);
    }

    @Test
    void rejectsHttpErrorsInvalidGzipAndInvalidHeadersWithoutCanonicalFiles() throws IOException {
        installValidResponses("valid");
        responses.put("title.basics.tsv.gz", new StubResponse(404, "missing".getBytes(UTF_8)));
        responses.put("title.akas.tsv.gz", new StubResponse(200, new byte[] {0x01, 0x02, 0x03}));
        responses.put("title.principals.tsv.gz", new StubResponse(200, gzip("bad\theader\n")));
        Path destination = tempDirectory.resolve("rejected");

        ImdbDatasetDownloader.DownloadSummary summary = downloader.downloadAll(
                destination, false, quietOutput());

        assertThat(summary).isEqualTo(new ImdbDatasetDownloader.DownloadSummary(3, 0, 3));
        assertThat(destination.resolve("title.basics.tsv.gz")).doesNotExist();
        assertThat(destination.resolve("title.akas.tsv.gz")).doesNotExist();
        assertThat(destination.resolve("title.principals.tsv.gz")).doesNotExist();
        assertNoPartialFiles(destination);
    }

    @Test
    void invalidExistingFileIsReplacedWithoutAnExplicitRefresh() throws IOException {
        installValidResponses("valid");
        Path destination = tempDirectory.resolve("invalid-existing");
        Files.createDirectories(destination);
        Files.writeString(destination.resolve("title.basics.tsv.gz"), "broken", UTF_8);

        ImdbDatasetDownloader.DownloadSummary summary = downloader.downloadAll(
                destination, false, quietOutput());

        assertThat(summary).isEqualTo(new ImdbDatasetDownloader.DownloadSummary(6, 0, 0));
        ImdbDataset dataset = ImdbDatasetCatalog.datasets().getFirst();
        ImdbDatasetDownloader.validate(destination.resolve(dataset.filename()), dataset);
    }

    private void installValidResponses(String marker) throws IOException {
        for (ImdbDataset dataset : ImdbDatasetCatalog.datasets()) {
            String contents = dataset.expectedHeader() + "\n" + marker + "\n";
            responses.put(dataset.filename(), new StubResponse(200, gzip(contents)));
        }
    }

    private void respond(HttpExchange exchange) throws IOException {
        String filename = exchange.getRequestURI().getPath().substring(1);
        requestCounts.computeIfAbsent(filename, ignored -> new AtomicInteger()).incrementAndGet();
        StubResponse response = responses.getOrDefault(
                filename, new StubResponse(404, "not found".getBytes(UTF_8)));
        exchange.getResponseHeaders().set("Content-Type", "application/gzip");
        exchange.sendResponseHeaders(response.status(), response.body().length);
        try (exchange; var body = exchange.getResponseBody()) {
            body.write(response.body());
        }
    }

    private static byte[] gzip(String contents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(contents.getBytes(UTF_8));
        }
        return bytes.toByteArray();
    }

    private static PrintStream quietOutput() {
        return new PrintStream(new ByteArrayOutputStream(), true, UTF_8);
    }

    private static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void assertNoPartialFiles(Path destination) throws IOException {
        try (var files = Files.list(destination)) {
            assertThat(files).noneMatch(path -> path.getFileName().toString().endsWith(".part"));
        }
    }

    private record StubResponse(int status, byte[] body) {
    }
}
