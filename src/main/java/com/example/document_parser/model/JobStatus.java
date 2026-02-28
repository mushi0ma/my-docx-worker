package com.example.document_parser.model;

/**
 * ИСПРАВЛЕНО: Статусы задачи вынесены в enum.
 * Раньше "SUCCESS", "ERROR", "PENDING" были хардкодными строками в 5 местах —
 * опечатка в любом из них ломала логику без ошибки компиляции.
 */
public enum JobStatus {
    ACCEPTED,   // принят в очередь
    PROCESSING, // воркер взял в работу
    PENDING,
    SUCCESS,    // парсинг завершён успешно
    ERROR,      // парсинг завершился с ошибкой
    NOT_FOUND   // jobId неизвестен системе
}