package com.moviefinder.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void invalidArgumentsProduceSafeProblemDetails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/movies");

        ResponseEntity<ProblemDetail> response = handler.handleInvalidRequest(
                new IllegalArgumentException("title must not be blank"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Invalid request");
        assertThat(response.getBody().getDetail()).isEqualTo("title must not be blank");
        assertThat(response.getBody().getProperties()).containsEntry("path", "/api/movies");
    }

    @Test
    void unexpectedExceptionsDoNotExposeInternalDetails() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/movies");

        ResponseEntity<ProblemDetail> response = handler.handleUnexpectedException(
                new RuntimeException("database password leaked"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo("An unexpected error occurred");
        assertThat(response.getBody().getDetail()).doesNotContain("password");
    }
}
