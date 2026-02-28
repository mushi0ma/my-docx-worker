package com.example.document_parser.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * ИСПРАВЛЕНО: Централизованная конфигурация хранилища.
 * Путь больше не захардкожен в двух местах — он читается из application.properties,
 * а тот — из env-переменной TEMP_DOCS_PATH.
 */
@Configuration
public class StorageConfig {

    @Value("${app.storage.temp-path}")
    private String tempPath;

    public Path getTempStoragePath() {
        Path path = Paths.get(tempPath);
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new RuntimeException("Не удалось создать директорию для временного хранения: " + path, e);
        }
        return path;
    }
}