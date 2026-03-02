package com.example.document_parser.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;

/**
 * Конфигурация Jackson 3 для Spring Boot 4.
 *
 * Ключевые отличия от Jackson 2:
 *
 *  1. Пакеты переехали:
 *     Jackson 2:  com.fasterxml.jackson.databind.SerializationFeature
 *     Jackson 3:  tools.jackson.databind.SerializationFeature  ← используем это
 *
 *  2. Новые дефолты в Jackson 3 (уже включены без явной настройки):
 *     - WRITE_DATES_AS_TIMESTAMPS = false  → даты как ISO-строки "2024-03-01T12:00:00"
 *     - FAIL_ON_EMPTY_BEANS = false        → пустые бины не бросают исключение
 *     - FAIL_ON_UNKNOWN_PROPERTIES = false → неизвестные поля в JSON игнорируются
 *
 *  3. JavaTimeModule больше не нужен — встроен в Jackson 3 core
 *
 * Оставляем явные disable() для документации и защиты от случайного переопределения.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder
                // Не падать на пустых бинах (дефолт в Jackson 3, явно для ясности)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                // Не падать если в JSON пришло поле которого нет в DTO (дефолт в Jackson 3)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}