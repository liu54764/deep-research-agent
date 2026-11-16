package com.dra.api.web;

import com.dra.common.error.DraException;
import com.dra.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DraException.class)
    public ResponseEntity<ProblemDetail> handleDra(DraException e) {
        HttpStatus status = httpStatus(e.code());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setTitle(e.code().name());
        problem.setProperty("code", e.code().name());
        problem.setProperty("retryable", e.retryable());
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(field -> field.getField() + ": " + fieldMessage(field))
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, detail);
    }

    @ExceptionHandler({MissingRequestHeaderException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ProblemDetail> handleBadRequest(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
        log.error("unexpected API error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "unexpected server error");
    }

    private static String fieldMessage(FieldError field) {
        return field.getDefaultMessage() == null ? "invalid value" : field.getDefaultMessage();
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail == null ? code.name() : detail);
        problem.setTitle(code.name());
        problem.setProperty("code", code.name());
        return ResponseEntity.status(status).body(problem);
    }

    private static HttpStatus httpStatus(ErrorCode code) {
        return switch (code) {
            case VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case TASK_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case IDEMPOTENCY_CONFLICT, TASK_TERMINAL, REPORT_NOT_READY -> HttpStatus.CONFLICT;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case TEMPORAL_UNAVAILABLE, PROGRESS_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
