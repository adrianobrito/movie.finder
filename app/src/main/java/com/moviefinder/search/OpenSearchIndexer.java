package com.moviefinder.search;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/** Local development entry point for creating and filling the OpenSearch read model. */
public final class OpenSearchIndexer {

    private OpenSearchIndexer() {
    }

    public static void main(String[] args) {
        Path source = Path.of("data", "imdb", "canonical");
        String url = System.getenv().getOrDefault("OPENSEARCH_URL", "http://localhost:9200");
        boolean recreate = false;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--source" -> source = Path.of(requiredValue(args, ++i, "--source"));
                    case "--url" -> url = requiredValue(args, ++i, "--url");
                    case "--recreate" -> recreate = true;
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            String username = System.getenv("OPENSEARCH_USERNAME");
            String password = System.getenv("OPENSEARCH_PASSWORD");
            OpenSearchReadModel model = new OpenSearchReadModel(URI.create(url), username, password);
            OpenSearchReadModel.IndexSummary summary = model.indexCanonical(source, recreate);
            System.out.printf("Indexed: movies=%d people=%d%n", summary.movies(), summary.people());
        } catch (IllegalArgumentException | IOException exception) {
            System.err.println("Indexing failed: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static String requiredValue(String[] args, int index, String option) {
        if (index >= args.length || args[index].isBlank()) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index];
    }
}
