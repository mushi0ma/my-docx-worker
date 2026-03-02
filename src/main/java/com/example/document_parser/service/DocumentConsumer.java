package com.example.document_parser.service;

import com.example.document_parser.config.StorageConfig;
import com.example.document_parser.dto.DocumentMetadataResponse;
import com.example.document_parser.dto.GenerateDocumentRequest;
import com.example.document_parser.entity.DocumentEntity;
import com.example.document_parser.model.JobStatus;
import com.example.document_parser.repository.DocumentRepository;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;

@Service
public class DocumentConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocumentConsumer.class);

    private final DocxParserService parserService;
    private final DocxGeneratorService generatorService;
    private final SelfCorrectionService selfCorrectionService;
    private final VectorizationService vectorizationService;
    private final WebhookService webhookService;
    private final StringRedisTemplate redisTemplate;
    private final DocumentRepository documentRepository;
    private final ObjectMapper objectMapper;
    private final Path tempStorage;

    @Value("${app.redis.result-ttl-seconds:86400}")
    private long resultTtlSeconds;

    public DocumentConsumer(DocxParserService parserService,
                            DocxGeneratorService generatorService,
                            SelfCorrectionService selfCorrectionService,
                            VectorizationService vectorizationService,
                            WebhookService webhookService,
                            StringRedisTemplate redisTemplate,
                            DocumentRepository documentRepository,
                            ObjectMapper objectMapper,
                            StorageConfig storageConfig) {
        this.parserService = parserService;
        this.generatorService = generatorService;
        this.selfCorrectionService = selfCorrectionService;
        this.vectorizationService = vectorizationService;
        this.webhookService = webhookService;
        this.redisTemplate = redisTemplate;
        this.documentRepository = documentRepository;
        this.objectMapper = objectMapper;
        this.tempStorage = storageConfig.getTempStoragePath();
    }

    @SuppressWarnings("unused")
    @RabbitListener(queues = "${app.rabbitmq.queue}")
    public void processDocument(String message) {
        if (message == null || message.isBlank()) {
            log.warn("⚠️ Пустое сообщение из очереди, игнорируем");
            return;
        }

        String[] parts = message.split("\\|");
        String jobId = parts[0];
        String taskType = parts.length > 1 ? parts[1] : "PARSE";
        String originalName = parts.length > 2 ? parts[2] : jobId + ".docx";

        log.info("⏳ [{}] Начало обработки {}: {}", jobId, taskType, originalName);

        Duration ttl = Duration.ofSeconds(resultTtlSeconds);

        DocumentEntity entity = documentRepository.findById(jobId)
                .orElse(new DocumentEntity(jobId, JobStatus.PROCESSING, originalName, taskType, null));
        entity.updateProgress(0, JobStatus.PROCESSING);
        documentRepository.save(entity);

        try {
            if ("GENERATE".equals(taskType)) {
                processGenerateTask(jobId, entity);
            } else {
                processParseTask(jobId, originalName, entity, ttl);
            }
        } catch (Exception e) {
            log.error("❌ [{}] Ошибка: {}", jobId, e.getMessage(), e);
            saveError(jobId, originalName, e.getMessage(), ttl);
            webhookService.sendWebhook(entity.getWebhookUrl(), jobId, "ERROR", e.getMessage(), null);
        } finally {
            if (!"GENERATE".equals(taskType)) {
                deleteQuietly(tempStorage.resolve(jobId + ".docx"));
            }
        }
    }

    private void processGenerateTask(String jobId, DocumentEntity entity) throws Exception {
        entity.updateProgress(10, JobStatus.PROCESSING);
        documentRepository.save(entity);

        String inputJson = redisTemplate.opsForValue().get("job:" + jobId + ":generate_input");
        if (inputJson == null) {
            throw new RuntimeException("Missing GenerateDocumentRequest input JSON in Redis");
        }

        redisTemplate.delete("job:" + jobId + ":generate_input");

        GenerateDocumentRequest request = objectMapper.readValue(inputJson, GenerateDocumentRequest.class);

        entity.updateProgress(50, JobStatus.PROCESSING);
        documentRepository.save(entity);

        File generatedFile;
        if (request.getTemplateId() != null && !request.getTemplateId().isBlank()) {
            Path templatePath = tempStorage.resolve("templates").resolve(request.getTemplateId() + ".docx");
            if (Files.exists(templatePath)) {
                try (InputStream is = Files.newInputStream(templatePath)) {
                    generatedFile = generatorService.generateDocument(request.getMetadata(), jobId, is);
                }
            } else {
                log.warn("Template {} not found, generating from scratch", request.getTemplateId());
                generatedFile = generatorService.generateDocument(request.getMetadata(), jobId, null);
            }
        } else {
            generatedFile = generatorService.generateDocument(request.getMetadata(), jobId, null);
        }

        Path targetPath = tempStorage.resolve(jobId + ".docx");
        java.nio.file.Files.copy(generatedFile.toPath(), targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        generatedFile.delete();

        entity.updateProgress(100, JobStatus.SUCCESS);
        documentRepository.save(entity);

        log.info("✅ [{}] Генерация завершена. Файл сохранен: {}", jobId, targetPath.toAbsolutePath());

        webhookService.sendWebhook(entity.getWebhookUrl(), jobId, "SUCCESS", "Generation complete",
                "/api/v1/documents/" + jobId + "/download");
    }

    @Transactional
    private void processParseTask(String jobId, String originalName, DocumentEntity entity, Duration ttl) throws Exception {
        Path filePath = tempStorage.resolve(jobId + ".docx");
        if (!filePath.toFile().exists()) {
            log.error("❌ [{}] Файл не найден: {}", jobId, filePath);
            saveError(jobId, originalName, "File not found on disk", ttl);
            return;
        }

        entity.updateProgress(10, JobStatus.PROCESSING);
        documentRepository.save(entity);

        DocumentMetadataResponse rawResponse = parserService.parseDocument(filePath.toFile(), originalName, jobId);

        entity.updateProgress(50, JobStatus.PROCESSING);
        documentRepository.save(entity);

        log.info("🤖 [{}] Запуск AI-проверки и самокоррекции...", jobId);
        // ИСПРАВЛЕНО 1: Правильное имя метода
        DocumentMetadataResponse cleanResponse = selfCorrectionService.validateAndCorrect(rawResponse);

        entity.updateProgress(70, JobStatus.PROCESSING);
        documentRepository.save(entity);

        String jsonResult = objectMapper.writeValueAsString(cleanResponse);
        redisTemplate.opsForValue().set("job:" + jobId, jsonResult, ttl);
        entity.setResultJson(jsonResult);

        entity.updateProgress(90, JobStatus.PROCESSING);
        documentRepository.save(entity);

        log.info("🧠 [{}] Запуск векторизации для RAG...", jobId);
        vectorizationService.vectorizeAndStore(cleanResponse, jobId);

        entity.updateProgress(100, JobStatus.SUCCESS);
        documentRepository.save(entity);

        log.info("✅ [{}] Парсинг и ИИ-обработка завершены успешно", jobId);

        webhookService.sendWebhook(entity.getWebhookUrl(), jobId, "SUCCESS", "Parsing and Vectorization complete",
                "/api/v1/documents/" + jobId);
    }

    private void saveError(String jobId, String originalName, String message, Duration ttl) {
        String safe = message != null ? message.replace("\"", "'") : "Unknown error";
        String errorJson = "{\"status\":\"" + JobStatus.ERROR.name() +
                "\",\"jobId\":\"" + jobId + "\",\"message\":\"" + safe + "\"}";
        try {
            redisTemplate.opsForValue().set("job:" + jobId, errorJson, ttl);
        } catch (Exception redisEx) {
            log.error("❌ Не удалось записать ошибку в Redis для {}", jobId, redisEx);
        }
        DocumentEntity err = new DocumentEntity(jobId, JobStatus.ERROR, originalName);
        err.setResultJson(safe);
        documentRepository.save(err);
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("⚠️ Не удалось удалить временный файл: {}", path, e);
        }
    }
}