package com.moviefinder.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.moviefinder.search.MovieSearchService.PersonResolutionException;
import com.moviefinder.search.PersonResolver;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleInvalidRequest(
            IllegalArgumentException exception,
            HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "Invalid request",
                exception.getMessage(),
                request.getRequestURI());
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadableRequest(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST,
                "Invalid request", "Request body must be valid JSON with the expected field types",
                request.getRequestURI()));
    }

    @ExceptionHandler(PersonResolutionException.class)
    ResponseEntity<ProblemDetail> handlePersonResolution(
            PersonResolutionException exception, HttpServletRequest request) {
        boolean missing = exception.resolution().status() == PersonResolver.Status.NOT_FOUND;
        HttpStatus status = missing ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        ProblemDetail problem = problem(status,
                missing ? "Person not found" : "Ambiguous person",
                exception.getMessage(), request.getRequestURI());
        problem.setProperty("role", exception.role());
        problem.setProperty("name", exception.name());
        if (!missing) {
            problem.setProperty("candidates", exception.resolution().candidates());
            problem.setProperty("totalCandidates", exception.resolution().totalCandidates());
        }
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request) {
        LOGGER.error(
                "Unhandled exception while processing {} {}",
                request.getMethod(),
                request.getRequestURI(),
                exception);

        ProblemDetail problem = problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error",
                "An unexpected error occurred",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, String path) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("path", path);
        return problem;
    }
}
