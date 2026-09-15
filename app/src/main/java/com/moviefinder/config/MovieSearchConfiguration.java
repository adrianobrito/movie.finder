package com.moviefinder.config;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.moviefinder.search.MovieSearchService;
import com.moviefinder.search.OpenSearchCompositeMovieLookup;
import com.moviefinder.search.OpenSearchPersonLookup;
import com.moviefinder.search.OpenSearchSearchClient;
import com.moviefinder.search.PersonResolver;

@Configuration
public class MovieSearchConfiguration {

    @Bean
    MovieSearchService movieSearchService(
            @Value("${OPENSEARCH_URL:http://localhost:9200}") String url,
            @Value("${OPENSEARCH_USERNAME:}") String username,
            @Value("${OPENSEARCH_PASSWORD:}") String password) {
        OpenSearchSearchClient client = new OpenSearchSearchClient(URI.create(url),
                username.isBlank() ? null : username,
                password.isBlank() ? null : password);
        return new MovieSearchService(new PersonResolver(new OpenSearchPersonLookup(client)),
                new OpenSearchCompositeMovieLookup(client));
    }
}
