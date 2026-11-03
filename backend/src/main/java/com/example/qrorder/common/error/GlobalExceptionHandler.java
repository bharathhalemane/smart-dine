package com.example.qrorder.common.error;

import jakarta.validation.ConstraintViolationException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler{

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> handleDomain(DomainException ex){
        return build(ex.code(), ex.getMessage(), List.of());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraint(ConstraintViolationException ex){
        List<FieldIssue> issues = ex.getConstraintViolations().stream()
                .map(v -> new FieldIssue(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, "Request validation failed", issues);
    }

    @ExceptionHandler(AccessDeniedException.class)
    void rethrowAccessDenied(AccessDeniedException ex){
        throw ex;
    }

    @ExceptionHandler(AuthenticationException.class)
    void rethrowAuthentication(AuthenticationException ex){
        throw ex;
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex){
        log.error("Unhandled exception", ex);
        return build(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred", List.of());
    }

    @Override
    protected  ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request){
        List<FieldIssue> issues = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> new FieldIssue(f.getField(), f.getDefaultMessage()))
                .toList();
        return asObject(build(ErrorCode.VALIDATION_FAILED, "Request validation failed", issues));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request){
        return asObject(build(ErrorCode.VALIDATION_FAILED, "Malformed request body:",List.of()));
    }

    private ResponseEntity<ProblemDetail> build(ErrorCode code, String message, List<FieldIssue> issues){
        HttpStatus status = code.status();
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, message);
        pd.setTitle(status.getReasonPhrase());
        pd.setProperty("code",code.name());
        pd.setProperty("traceId",MDC.get(CorrelationIdFilter.MDC_KEY));
        if(!issues.isEmpty()){
            pd.setProperty("errors", issues);
        }
        return ResponseEntity.status(status).body(pd);
    }

    private static ResponseEntity<Object> asObject(ResponseEntity<ProblemDetail> entity) {
        return ResponseEntity.status(entity.getStatusCode()).body(entity.getBody());
    }

    public record FieldIssue(String field, String message) {}

}

