package com.devpulse.backend.exception;

import com.devpulse.backend.dto.ErrorResponse;
import com.devpulse.backend.security.RefreshTokenCookies;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.List;

/**
 * Turns every exception thrown by a controller into an {@link ErrorResponse}.
 * Errors raised before a controller runs (missing/invalid token, forbidden)
 * are written by {@link com.devpulse.backend.security.JsonSecurityErrorHandler}
 * in the same shape.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final RefreshTokenCookies refreshTokenCookies;

    public GlobalExceptionHandler(RefreshTokenCookies refreshTokenCookies) {
        this.refreshTokenCookies = refreshTokenCookies;
    }

    // ---------- 400 ----------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        List<ErrorResponse.FieldError> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(e -> new ErrorResponse.FieldError(e.getField(), e.getDefaultMessage()))
                .toList();

        return build(HttpStatus.BAD_REQUEST, "Validation failed", request, errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {

        List<ErrorResponse.FieldError> errors = ex.getConstraintViolations()
                .stream()
                .map(v -> new ErrorResponse.FieldError(
                        v.getPropertyPath().toString(), v.getMessage()))
                .toList();

        return build(HttpStatus.BAD_REQUEST, "Validation failed", request, errors);
    }

    @ExceptionHandler(FieldValidationException.class)
    public ResponseEntity<ErrorResponse> handleFieldValidation(
            FieldValidationException ex, HttpServletRequest request) {

        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request,
                List.of(new ErrorResponse.FieldError(ex.getField(), ex.getMessage())));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {

        return build(HttpStatus.BAD_REQUEST, "Request body is missing or malformed", request);
    }

    @ExceptionHandler({
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<ErrorResponse> handleBadParameter(
            Exception ex, HttpServletRequest request) {

        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    // ---------- 401 / 403 ----------

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(
            InvalidCredentialsException ex, HttpServletRequest request) {

        return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), request);
    }

    // The session is over: also delete the useless cookie from the browser.
    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRefreshToken(
            InvalidRefreshTokenException ex, HttpServletRequest request) {

        ResponseEntity<ErrorResponse> response = build(HttpStatus.UNAUTHORIZED, ex.getMessage(), request);

        if (!ex.shouldClearCookie()) {
            return response;
        }

        return ResponseEntity.status(response.getStatusCode())
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.clear())
                .body(response.getBody());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(
            AuthenticationException ex, HttpServletRequest request) {

        return build(HttpStatus.UNAUTHORIZED, "Authentication required", request);
    }

    // Thrown by @PreAuthorize inside a controller call.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {

        return build(HttpStatus.FORBIDDEN, "You do not have permission to do this", request);
    }

    // ---------- 404 / 405 / 409 / 415 ----------

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
            ResourceNotFoundException ex, HttpServletRequest request) {

        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoRoute(
            NoResourceFoundException ex, HttpServletRequest request) {

        return build(HttpStatus.NOT_FOUND, "No endpoint at this path", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {

        return build(HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage(), request);
    }

    @ExceptionHandler({EmailAlreadyExistsException.class, ConflictException.class})
    public ResponseEntity<ErrorResponse> handleConflict(
            RuntimeException ex, HttpServletRequest request) {

        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {

        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getMessage(), request);
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ErrorResponse> handleTooManyRequests(
            TooManyRequestsException ex, HttpServletRequest request) {

        ResponseEntity<ErrorResponse> response = build(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), request);
        return ResponseEntity.status(response.getStatusCode())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(response.getBody());
    }

    // ---------- 5xx ----------

    @ExceptionHandler(AIServiceException.class)
    public ResponseEntity<ErrorResponse> handleAIService(
            AIServiceException ex, HttpServletRequest request) {

        log.warn("AI provider failed on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_GATEWAY, "The AI provider could not complete the request", request);
    }

    // Last resort: log the details, never send a stack trace to the client.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception ex, HttpServletRequest request) {

        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong", request);
    }

    // ---------- helpers ----------

    private ResponseEntity<ErrorResponse> build(
            HttpStatus status, String message, HttpServletRequest request) {

        return build(status, message, request, null);
    }

    private ResponseEntity<ErrorResponse> build(
            HttpStatus status, String message, HttpServletRequest request,
            List<ErrorResponse.FieldError> errors) {

        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI(),
                errors
        );

        return ResponseEntity.status(status).body(body);
    }
}
