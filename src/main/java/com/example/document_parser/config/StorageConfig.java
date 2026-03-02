package com.example.document_parser.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Централизованная конфигурация хранилища.
 *
 * Исправления:
 * - Путь инициализируется один раз в @PostConstruct, а не при каждом вызове
 * - getTempStoragePath() теперь просто возвращает готовый Path — thread-safe
 * - Добавлено логирование успешной инициализации
 */
@Configuration
public class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Value("${app.storage.temp-path}")
    private String tempPath;

    // Вычисляется один раз при старте приложения
    private Path resolvedTempPath;

    @PostConstruct
    public void init() {
        resolvedTempPath = Paths.get(tempPath);
        try {
            Files.createDirectories(resolvedTempPath);
            log.info("Temp storage initialized at: {}", resolvedTempPath.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException(
                    "Не удалось создать директорию для временного хранения: " + resolvedTempPath, e);
        }
    }

    /**
     * Возвращает готовый Path. Thread-safe — поле задаётся один раз в @PostConstruct.
     */
    public Path getTempStoragePath() {
        return resolvedTempPath;
    }
}