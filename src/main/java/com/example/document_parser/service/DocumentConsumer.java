package com.example.document_parser.service;

import com.example.document_parser.dto.DocumentMetadataResponse;
import com.example.document_parser.entity.DocumentEntity;
import com.example.document_parser.repository.DocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

@Service
public class DocumentConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocumentConsumer.class);

    private final DocxParserService parserService;
    private final StringRedisTemplate redisTemplate;
    private final DocumentRepository documentRepository; // Добавили репозиторий БД

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path tempStorage = Paths.get("/home/mushi/temp_docs");

    // Обновили конструктор, чтобы Spring инжектил репозиторий
    public DocumentConsumer(DocxParserService parserService,
                            StringRedisTemplate redisTemplate,
                            DocumentRepository documentRepository) {
        this.parserService = parserService;
        this.redisTemplate = redisTemplate;
        this.documentRepository = documentRepository;
    }

    @SuppressWarnings("unused")
    @RabbitListener(queues = "${app.rabbitmq.queue}")
    public void processDocument(String jobId) {
        log.info("⏳ Worker поймал задачу: {}", jobId);
        Path filePath = tempStorage.resolve(jobId + ".docx");
        File file = filePath.toFile();

        try {
            if (!file.exists()) {
                log.error("❌ Файл не найден: {}", filePath);
                redisTemplate.opsForValue().set("job:" + jobId, "{\"status\":\"ERROR\", \"message\":\"File not found\"}", Duration.ofHours(24));
                documentRepository.save(new DocumentEntity(jobId, "ERROR", "File not found"));
                return;
            }

            DocumentMetadataResponse response = parserService.parseDocument(file);
            String jsonResult = objectMapper.writeValueAsString(response);

            redisTemplate.opsForValue().set("job:" + jobId, jsonResult, Duration.ofHours(24));
            documentRepository.save(new DocumentEntity(jobId, "SUCCESS", jsonResult));

            log.info("✅ Задача {} успешно выполнена и сохранена!", jobId);

        } catch (Exception e) {
            log.error("❌ Ошибка при парсинге задачи {}", jobId, e);
            redisTemplate.opsForValue().set("job:" + jobId, "{\"status\":\"ERROR\", \"message\":\"" + e.getMessage() + "\"}", Duration.ofHours(24));
            documentRepository.save(new DocumentEntity(jobId, "ERROR", e.getMessage()));
        } finally {
            // ИДЕАЛЬНОЕ ПОВЕДЕНИЕ: Файл будет удален ВСЕГДА, при любом исходе
            try {
                Files.deleteIfExists(filePath);
            } catch (IOException e) {
                log.error("⚠️ Не удалось удалить временный файл: {}", filePath, e);
            }
        }
    }
}