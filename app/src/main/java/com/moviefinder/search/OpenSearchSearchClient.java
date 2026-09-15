package com.moviefinder.search;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Small HTTP client shared by the person and ID-based movie search adapters. */
public final class OpenSearchSearchClient {

    private final URI endpoint;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final String authorization;

    public OpenSearchSearchClient(URI endpoint) {
        this(endpoint, null, null);
    }

    public OpenSearchSearchClient(URI endpoint, String username, String password) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (!"http".equals(endpoint.getScheme()) && !"https".equals(endpoint.getScheme())) {
            throw new IllegalArgumentException("OpenSearch URL must use http or https");
        }
        if ((username == null) != (password == null)) {
            throw new IllegalArgumentException("both OpenSearch username and password are required");
        }
        this.endpoint = URI.create(endpoint.toString().replaceAll("/+$", "") + "/");
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        authorization = username == null ? null : "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(UTF_8));
    }

    ObjectNode objectNode() {
        return json.createObjectNode();
    }

    JsonNode search(String index, ObjectNode query) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint.resolve(index + "/_search"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(query), UTF_8));
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while searching OpenSearch", exception);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = response.body();
            throw new IOException("POST " + index + "/_search failed with HTTP " + response.statusCode()
                    + ": " + body.substring(0, Math.min(body.length(), 500)));
        }
        try {
            return json.readTree(response.body());
        } catch (RuntimeException exception) {
            throw new IOException("invalid OpenSearch search response", exception);
        }
    }

    static JsonNode hits(JsonNode response) throws IOException {
        JsonNode hits = response.path("hits");
        if (!hits.path("hits").isArray() || !hits.path("total").path("value").isNumber()) {
            throw new IOException("OpenSearch search response has no hits or total");
        }
        return hits;
    }
}
