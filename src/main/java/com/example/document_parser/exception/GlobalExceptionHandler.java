package com.example.document_parser.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.time.Instant;

/**
 * ИСПРАВЛЕНО: Единая точка обработки всех ошибок.
 * Контроллеры больше не содержат try-catch и Map.of("error",...).
 * Используем RFC 7807 ProblemDetail (встроен в Spring Boot 3+) — стандартный
 * формат ошибок API.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppExceptions.JobNotFoundException.class)
    public ProblemDetail handleJobNotFound(AppExceptions.JobNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "job-not-found");
    }

    @ExceptionHandler(AppExceptions.InvalidJobIdException.class)
    public ProblemDetail handleInvalidJobId(AppExceptions.InvalidJobIdException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage(), "invalid-job-id");
    }

    @ExceptionHandler(AppExceptions.InvalidFileException.class)
    public ProblemDetail handleInvalidFile(AppExceptions.InvalidFileException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage(), "invalid-file");
    }

    @ExceptionHandler(AppExceptions.JobFailedException.class)
    public ProblemDetail handleJobFailed(AppExceptions.JobFailedException ex) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), "job-failed");
    }

    @ExceptionHandler(AppExceptions.ExportException.class)
    public ProblemDetail handleExport(AppExceptions.ExportException ex) {
        log.error("Export error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), "export-error");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleMaxSize(MaxUploadSizeExceededException ex) {
        log.warn("File too large: {}", ex.getMessage());
        return problem(HttpStatus.PAYLOAD_TOO_LARGE,
                "Файл слишком большой. Максимум: 50MB", "file-too-large");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error", "internal-error");
    }

    @ExceptionHandler(tools.jackson.core.JacksonException.class)
    public ProblemDetail handleJacksonException(tools.jackson.core.JacksonException ex) {
        log.error("JSON processing error: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST,
                "Invalid JSON: " + ex.getOriginalMessage(), "json-error");
    }

    @ExceptionHandler(RuntimeException.class)
    public ProblemDetail handleRuntimeException(RuntimeException ex) {
        log.error("Runtime exception: {}", ex.getMessage(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR,
                ex.getMessage() != null ? ex.getMessage() : "Unexpected error", "runtime-error");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage(), "invalid-argument");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResourceFound(NoResourceFoundException ex) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                "Эндпоинт не найден. Убедитесь, что вы используете /export/tsv, а не просто /tsv");
    }

    private ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create("https://api.document-parser.local/errors/" + errorCode));
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}