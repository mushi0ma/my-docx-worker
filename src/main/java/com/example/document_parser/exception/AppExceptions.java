package com.example.document_parser.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * ИСПРАВЛЕНО: Вместо ручных try-catch в контроллере с Map.of("error", ...)
 * бросаем типизированные исключения — GlobalExceptionHandler перехватывает их.
 * Контроллер становится "тонким": только маршрутизация, никакой логики ошибок.
 */
public final class AppExceptions {

    private AppExceptions() {}

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class JobNotFoundException extends RuntimeException {
        public JobNotFoundException(String jobId) {
            super("Задача не найдена или ещё выполняется: " + jobId);
        }
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class InvalidJobIdException extends RuntimeException {
        public InvalidJobIdException(String jobId) {
            super("Некорректный формат jobId: " + jobId);
        }
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class InvalidFileException extends RuntimeException {
        public InvalidFileException(String message) {
            super(message);
        }
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public static class JobFailedException extends RuntimeException {
        public JobFailedException(String jobId, String reason) {
            super("Задача " + jobId + " завершилась с ошибкой: " + reason);
        }
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public static class ExportException extends RuntimeException {
        public ExportException(String format, Throwable cause) {
            super("Ошибка экспорта в формат " + format, cause);
        }
    }
}