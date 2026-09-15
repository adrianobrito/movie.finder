package com.moviefinder.search;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Creates the versioned OpenSearch read model and indexes canonical IMDb NDJSON in bounded batches. */
public final class OpenSearchReadModel {

    private static final int BATCH_SIZE = 500;
    private static final String PEOPLE = "people";
    private static final String MOVIES = "movies";

    private final URI endpoint;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final String authorization;

    public OpenSearchReadModel(URI endpoint) {
        this(endpoint, null, null);
    }

    public OpenSearchReadModel(URI endpoint, String username, String password) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (!"http".equals(endpoint.getScheme()) && !"https".equals(endpoint.getScheme())) {
            throw new IllegalArgumentException("OpenSearch URL must use http or https");
        }
        this.endpoint = URI.create(endpoint.toString().replaceAll("/+$", "") + "/");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        if ((username == null) != (password == null)) {
            throw new IllegalArgumentException("both OpenSearch username and password are required");
        }
        authorization = username == null ? null : "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(UTF_8));
    }

    /** Existing indexes are retained unless recreate is explicitly requested. */
    public void createIndexes(boolean recreate) throws IOException {
        createIndex(PEOPLE, "opensearch/people-v1.json", recreate);
        createIndex(MOVIES, "opensearch/movies-v1.json", recreate);
    }

    /** Indexes people first so movie names can be resolved with realtime multi-get. */
    public IndexSummary indexCanonical(Path directory, boolean recreate) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Path people = directory.resolve("people.ndjson");
        Path movies = directory.resolve("movies.ndjson");
        if (!Files.isRegularFile(people) || !Files.isRegularFile(movies)) {
            throw new IOException("canonical people.ndjson and movies.ndjson are required in " + directory);
        }
        createIndexes(recreate);
        long peopleCount = indexPeople(people);
        long movieCount = indexMovies(movies);
        return new IndexSummary(movieCount, peopleCount);
    }

    private void createIndex(String index, String resource, boolean recreate) throws IOException {
        HttpResponse<String> existence = request("HEAD", index, null, null);
        if (existence.statusCode() != 404 && (existence.statusCode() < 200 || existence.statusCode() >= 300)) {
            throw failure("HEAD " + index, existence);
        }
        if (existence.statusCode() != 404) {
            if (!recreate) {
                return;
            }
            requireSuccess("DELETE " + index, request("DELETE", index, null, null));
        }
        String mapping;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("missing index mapping: " + resource);
            }
            mapping = new String(input.readAllBytes(), UTF_8);
        }
        requireSuccess("PUT " + index, request("PUT", index, mapping, "application/json"));
    }

    private long indexPeople(Path file) throws IOException {
        long count = 0;
        List<ObjectNode> batch = new ArrayList<>(BATCH_SIZE);
        try (BufferedReader reader = Files.newBufferedReader(file, UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                batch.add(parseRecord(line, "nm"));
                if (batch.size() == BATCH_SIZE) {
                    bulk(PEOPLE, batch);
                    count += batch.size();
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            bulk(PEOPLE, batch);
            count += batch.size();
        }
        return count;
    }

    private long indexMovies(Path file) throws IOException {
        long count = 0;
        List<ObjectNode> batch = new ArrayList<>(BATCH_SIZE);
        try (BufferedReader reader = Files.newBufferedReader(file, UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                batch.add(parseRecord(line, "tt"));
                if (batch.size() == BATCH_SIZE) {
                    enrichMovieNames(batch);
                    bulk(MOVIES, batch);
                    count += batch.size();
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            enrichMovieNames(batch);
            bulk(MOVIES, batch);
            count += batch.size();
        }
        return count;
    }

    private ObjectNode parseRecord(String line, String idPrefix) throws IOException {
        try {
            JsonNode node = json.readTree(line);
            if (!(node instanceof ObjectNode record)) {
                throw new IOException("canonical record must be a JSON object");
            }
            String id = record.path("id").asText();
            if (!id.matches(idPrefix + "\\d+")) {
                throw new IOException("invalid canonical " + idPrefix + " ID: " + id);
            }
            return record;
        } catch (RuntimeException exception) {
            throw new IOException("invalid canonical JSON record", exception);
        }
    }

    private void enrichMovieNames(List<ObjectNode> movies) throws IOException {
        TreeSet<String> personIds = new TreeSet<>();
        for (ObjectNode movie : movies) {
            addIds(personIds, movie.path("castPersonIds"));
            addIds(personIds, movie.path("directorPersonIds"));
        }
        Map<String, String> names = new HashMap<>();
        if (!personIds.isEmpty()) {
            ObjectNode payload = json.createObjectNode();
            ArrayNode ids = payload.putArray("ids");
            personIds.forEach(ids::add);
            HttpResponse<String> response = request("POST", PEOPLE + "/_mget",
                    json.writeValueAsString(payload), "application/json");
            requireSuccess("POST people/_mget", response);
            JsonNode documents = json.readTree(response.body()).path("docs");
            if (!documents.isArray()) {
                throw new IOException("OpenSearch multi-get response has no docs array");
            }
            for (JsonNode document : documents) {
                String id = document.path("_id").asText();
                if (!document.path("found").asBoolean()) {
                    throw new IOException("movie references a person missing from the people index: " + id);
                }
                String name = document.path("_source").path("primaryName").asText();
                if (name.isBlank()) {
                    throw new IOException("person has no primaryName in the people index: " + id);
                }
                names.put(id, name);
            }
        }
        for (ObjectNode movie : movies) {
            setNames(movie, "castPersonIds", "castNames", names);
            setNames(movie, "directorPersonIds", "directorNames", names);
        }
    }

    private static void addIds(TreeSet<String> ids, JsonNode values) throws IOException {
        if (!values.isArray()) {
            throw new IOException("canonical movie person IDs must be arrays");
        }
        for (JsonNode value : values) {
            if (!value.isTextual()) {
                throw new IOException("canonical movie person ID must be text");
            }
            ids.add(value.asText());
        }
    }

    private static void setNames(ObjectNode movie, String idField, String nameField, Map<String, String> names)
            throws IOException {
        TreeSet<String> sorted = new TreeSet<>();
        for (JsonNode id : movie.path(idField)) {
            String name = names.get(id.asText());
            if (name == null) {
                throw new IOException("person name not returned by OpenSearch: " + id.asText());
            }
            sorted.add(name);
        }
        ArrayNode values = movie.putArray(nameField);
        sorted.forEach(values::add);
    }

    private void bulk(String index, List<ObjectNode> records) throws IOException {
        StringBuilder body = new StringBuilder();
        for (ObjectNode record : records) {
            ObjectNode action = json.createObjectNode();
            action.putObject("index").put("_id", record.path("id").asText());
            body.append(json.writeValueAsString(action)).append('\n');
            body.append(json.writeValueAsString(record)).append('\n');
        }
        HttpResponse<String> response = request("POST", index + "/_bulk", body.toString(),
                "application/x-ndjson");
        requireSuccess("POST " + index + "/_bulk", response);
        JsonNode result = json.readTree(response.body());
        if (!result.path("errors").isBoolean()) {
            throw new IOException("OpenSearch bulk response has no errors flag");
        }
        if (result.path("errors").asBoolean()) {
            for (JsonNode item : result.path("items")) {
                JsonNode operation = item.path("index");
                if (operation.has("error")) {
                    throw new IOException("OpenSearch rejected " + index + " ID " + operation.path("_id").asText()
                            + ": " + operation.path("error").toString());
                }
            }
            throw new IOException("OpenSearch reported bulk indexing errors for " + index);
        }
    }

    private HttpResponse<String> request(String method, String path, String body, String contentType)
            throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint.resolve(path))
                .timeout(Duration.ofMinutes(2));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, UTF_8));
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while calling OpenSearch", exception);
        }
    }

    private static void requireSuccess(String operation, HttpResponse<String> response) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw failure(operation, response);
        }
    }

    private static IOException failure(String operation, HttpResponse<String> response) {
        String body = response.body();
        return new IOException(operation + " failed with HTTP " + response.statusCode() + ": "
                + body.substring(0, Math.min(body.length(), 500)));
    }

    public record IndexSummary(long movies, long people) {
    }
}
