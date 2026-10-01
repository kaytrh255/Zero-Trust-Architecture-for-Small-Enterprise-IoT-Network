package com.yak.zerotrust.exception;

import com.yak.zerotrust.dto.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Objects;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(UsernameAlreadyExistsException.class)
    public ResponseEntity<ApiError> handleUsernameConflict(
            UsernameAlreadyExistsException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, "CONFLICT", exception.getMessage(), request);
    }

    @ExceptionHandler(PolicyConflictException.class)
    public ResponseEntity<ApiError> handlePolicyConflict(
            PolicyConflictException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, "CONFLICT", exception.getMessage(), request);
    }

    @ExceptionHandler(DeviceConflictException.class)
    public ResponseEntity<ApiError> handleDeviceConflict(
            DeviceConflictException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, "CONFLICT", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidDeviceOwnerException.class)
    public ResponseEntity<ApiError> handleInvalidDeviceOwner(
            InvalidDeviceOwnerException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_DEVICE_OWNER", exception.getMessage(), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataConflict(
            DataIntegrityViolationException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.CONFLICT, "CONFLICT", "A record with the same unique value already exists", request);
    }

    @ExceptionHandler({DeviceNotFoundException.class, PolicyNotFoundException.class, UserNotFoundException.class})
    public ResponseEntity<ApiError> handleMissingEntity(HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Requested resource was not found", request);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiError> handleNotFound(HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(this::validationMessage)
                .orElse("Request validation failed");
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Request body is missing or invalid", request);
    }

    @ExceptionHandler(InvalidAuditQueryException.class)
    public ResponseEntity<ApiError> handleInvalidAuditQuery(
            InvalidAuditQueryException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_AUDIT_QUERY", exception.getMessage(), request);
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleInvalidParameter(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "A request parameter has an invalid value", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You do not have permission to perform this operation", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthenticationFailure(HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid username or password", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        log.error("Unexpected API error at {}", request.getRequestURI(), exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "An unexpected error occurred", request);
    }

    private String validationMessage(FieldError fieldError) {
        return fieldError.getField() + ": " + Objects.requireNonNullElse(
                fieldError.getDefaultMessage(),
                "is invalid"
        );
    }

    private ResponseEntity<ApiError> error(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request
    ) {
        ApiError body = new ApiError(Instant.now(), status.value(), code, message, request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
