package com.example.document_parser.service;

import com.example.document_parser.config.StorageConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Сборщик мусора для временных файлов.
 *
 * Исправления:
 * - Оба пути (tempDocs + images) инжектируются через StorageConfig
 * - Никакого System.getenv() в полях — это выполняется до Spring-инициализации
 * - Каждый файл логируется отдельно при ошибке — не прерываем весь проход
 * - Пустые шард-директории удаляются после очистки
 */
@Service
public class FileCleanupService {

    private static final Logger log = LoggerFactory.getLogger(FileCleanupService.class);

    @Value("${app.cleanup.max-age-hours:24}")
    private int maxAgeHours;

    private final Path tempDocsDir;
    private final Path imagesDir;

    public FileCleanupService(StorageConfig storageConfig) {
        this.tempDocsDir = storageConfig.getTempStoragePath();
        this.imagesDir = storageConfig.getImagesStoragePath();
    }

    @Scheduled(fixedRateString = "${app.cleanup.interval-ms:3600000}")
    public void cleanUpOldFiles() {
        log.info("🧹 Запуск Сборщика Мусора: файлы старше {} часов...", maxAgeHours);
        Instant threshold = Instant.now().minus(maxAgeHours, ChronoUnit.HOURS);

        int deleted = cleanDirectory(tempDocsDir, threshold);
        deleted += cleanDirectory(imagesDir, threshold);

        log.info("✨ Сборка мусора завершена. Удалено файлов: {}", deleted);
    }

    private int cleanDirectory(Path directory, Instant threshold) {
        if (!Files.exists(directory)) {
            log.debug("Directory does not exist, skipping cleanup: {}", directory);
            return 0;
        }

        final int[] deletedCount = {0};

        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        if (attrs.lastModifiedTime().toInstant().isBefore(threshold)) {
                            Files.delete(file);
                            deletedCount[0]++;
                            log.debug("🗑️ Удалён: {}", file.getFileName());
                        }
                    } catch (IOException e) {
                        // Не прерываем весь проход из-за одного файла
                        log.warn("⚠️ Не удалось удалить файл {}: {}", file, e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("⚠️ Пропущен недоступный файл {}: {}", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    // Корневую папку не трогаем
                    if (dir.equals(directory)) return FileVisitResult.CONTINUE;

                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                        if (!stream.iterator().hasNext()) {
                            Files.delete(dir);
                            log.debug("📂 Удалена пустая папка: {}", dir.getFileName());
                        }
                    } catch (IOException e) {
                        log.warn("⚠️ Не удалось удалить директорию {}: {}", dir, e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("❌ Ошибка при обходе директории {}: {}", directory, e.getMessage());
        }

        return deletedCount[0];
    }
}