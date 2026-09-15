package com.moviefinder.ingestion;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/** Describes one downloadable IMDb non-commercial dataset. */
public record ImdbDataset(String filename, List<String> headerColumns) {

    public ImdbDataset {
        Objects.requireNonNull(filename, "filename");
        headerColumns = List.copyOf(headerColumns);
    }

    public URI url(URI baseUrl) {
        return baseUrl.resolve(filename);
    }

    public String expectedHeader() {
        return String.join("\t", headerColumns);
    }
}
