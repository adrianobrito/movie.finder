package com.moviefinder.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
