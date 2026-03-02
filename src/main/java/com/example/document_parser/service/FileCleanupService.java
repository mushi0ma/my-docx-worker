package com.example.document_parser.service;

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

@Service
public class FileCleanupService {

    private static final Logger log = LoggerFactory.getLogger(FileCleanupService.class);

    // Берем значение из properties, по умолчанию 24 часа
    @Value("${app.cleanup.max-age-hours:24}")
    private int maxAgeHours;

    private final Path tempDocsDir = Paths.get(System.getenv().getOrDefault("STORAGE_PATH", "/app/temp_docs"));
    private final Path imagesDir = Paths.get(System.getenv().getOrDefault("IMAGE_STORAGE_PATH", "/app/images"));

    // Запускаем каждый час (3 600 000 миллисекунд)
    @Scheduled(fixedRateString = "3600000")
    public void cleanUpOldFiles() {
        log.info("🧹 Запуск Сборщика Мусора: поиск файлов и папок старше {} часов...", maxAgeHours);
        Instant threshold = Instant.now().minus(maxAgeHours, ChronoUnit.HOURS);

        int deletedFiles = cleanDirectory(tempDocsDir, threshold);
        deletedFiles += cleanDirectory(imagesDir, threshold);

        log.info("✨ Сборка мусора завершена. Удалено файлов: {}", deletedFiles);
    }

    /**
     * Рекурсивный проход по директории. Удаляет старые файлы и пустые папки.
     */
    private int cleanDirectory(Path directory, Instant threshold) {
        if (!Files.exists(directory)) return 0;

        final int[] deletedCount = {0};

        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                // 1. Сначала заходим в файлы
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.lastModifiedTime().toInstant().isBefore(threshold)) {
                        Files.delete(file);
                        deletedCount[0]++;
                        log.debug("🗑️ Удален старый файл: {}", file.getFileName());
                    }
                    return FileVisitResult.CONTINUE;
                }

                // 2. Когда выходим из папки, проверяем, не стала ли она пустой
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    // Корневую папку не удаляем, только вложенные
                    if (!dir.equals(directory)) {
                        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                            if (!stream.iterator().hasNext()) { // Если папка пуста
                                Files.delete(dir);
                                log.debug("📂 Удалена пустая папка-шард: {}", dir.getFileName());
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("❌ Ошибка при очистке директории {}: {}", directory, e.getMessage());
        }

        return deletedCount[0];
    }
}