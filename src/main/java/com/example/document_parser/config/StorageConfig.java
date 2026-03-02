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
 * Централизованная конфигурация путей хранилища.
 *
 * Оба пути (temp docs + images) управляются через Spring @Value,
 * инициализируются один раз в @PostConstruct и доступны через геттеры.
 */
@Configuration
public class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Value("${app.storage.temp-path}")
    private String tempPath;

    @Value("${app.storage.images-path}")
    private String imagesPath;

    private Path resolvedTempPath;
    private Path resolvedImagesPath;

    @PostConstruct
    public void init() {
        resolvedTempPath = createDirectory(tempPath, "Temp docs");
        resolvedImagesPath = createDirectory(imagesPath, "Images");
        log.info("Storage initialized. tempDocs={}, images={}",
                resolvedTempPath.toAbsolutePath(),
                resolvedImagesPath.toAbsolutePath());
    }

    public Path getTempStoragePath() {
        return resolvedTempPath;
    }

    public Path getImagesStoragePath() {
        return resolvedImagesPath;
    }

    private Path createDirectory(String pathStr, String label) {
        Path path = Paths.get(pathStr);
        try {
            Files.createDirectories(path);
            log.debug("{} storage ready at: {}", label, path.toAbsolutePath());
            return path;
        } catch (IOException e) {
            throw new RuntimeException(
                    "Не удалось создать директорию [" + label + "]: " + path, e);
        }
    }
}