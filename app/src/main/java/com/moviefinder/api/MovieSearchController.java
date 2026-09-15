package com.moviefinder.api;

import java.io.IOException;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moviefinder.search.MovieSearchService;

@RestController
@RequestMapping("/api/movies")
public class MovieSearchController {

    private final MovieSearchService search;

    public MovieSearchController(MovieSearchService search) {
        this.search = search;
    }

    @PostMapping("/search")
    public MovieSearchService.SearchResponse search(@RequestBody MovieSearchService.SearchRequest request)
            throws IOException {
        return search.search(request);
    }
}
