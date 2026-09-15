package com.moviefinder.search;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

class OpenSearchReadModelTest {

    @TempDir
    Path temp;

    @Test
    void reportsTheFailedIdFromAnOtherwiseSuccessfulBulkResponse() throws IOException {
        Path canonical = Files.createDirectory(temp.resolve("canonical"));
        Files.writeString(canonical.resolve("people.ndjson"),
                "{\"id\":\"nm123\",\"primaryName\":\"Example Person\",\"normalizedName\":\"example person\","
                        + "\"professions\":[],\"knownForTitleIds\":[]}\n", UTF_8);
        Files.writeString(canonical.resolve("movies.ndjson"), "", UTF_8);
        AtomicReference<String> bulkBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            byte[] response;
            int status;
            if ("HEAD".equals(method)) {
                status = 404;
                response = new byte[0];
            } else if ("PUT".equals(method) && ("/people".equals(path) || "/movies".equals(path))) {
                status = 200;
                response = "{}".getBytes(UTF_8);
            } else if ("POST".equals(method) && "/people/_bulk".equals(path)) {
                bulkBody.set(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
                status = 200;
                response = ("{\"errors\":true,\"items\":[{\"index\":{\"_id\":\"nm123\","
                        + "\"error\":{\"type\":\"mapper_parsing_exception\",\"reason\":\"bad document\"}}}]}" )
                        .getBytes(UTF_8);
            } else {
                status = 500;
                response = "unexpected request".getBytes(UTF_8);
            }
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            OpenSearchReadModel model = new OpenSearchReadModel(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            assertThatThrownBy(() -> model.indexCanonical(canonical, false))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("nm123", "mapper_parsing_exception");
            assertThat(bulkBody.get()).startsWith("{\"index\":{\"_id\":\"nm123\"}}\n")
                    .endsWith("\n");
        } finally {
            server.stop(0);
        }
    }
}
