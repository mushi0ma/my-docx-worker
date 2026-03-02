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
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

@Service
public class DocumentConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocumentConsumer.class);
    private static final String MDC_JOB_ID_KEY = "jobId";

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

        // 1. ПОДКЛЮЧАЕМ MDC: Теперь все логи в этом потоке (даже в других сервисах)
        // будут содержать jobId
        MDC.put(MDC_JOB_ID_KEY, jobId);

        try {
            log.info("Processing started. taskType={}, file={}", taskType, originalName);

            Duration ttl = Duration.ofSeconds(resultTtlSeconds);
            DocumentEntity entity = documentRepository.findById(jobId)
                    .orElse(new DocumentEntity(jobId, JobStatus.PROCESSING, originalName, taskType, null));
            entity.updateProgress(0, JobStatus.PROCESSING);
            documentRepository.save(entity);

            if ("GENERATE".equals(taskType)) {
                processGenerateTask(jobId, entity);
            } else {
                processParseTask(jobId, originalName, entity, ttl);
            }
        } catch (Exception e) {
            log.error("Processing failed. taskType={}, file={}, error={}",
                    taskType, originalName, e.getMessage(), e);
            saveError(jobId, originalName, e.getMessage(), Duration.ofSeconds(resultTtlSeconds));
        } finally {
            if (!"GENERATE".equals(taskType)) {
                deleteQuietly(tempStorage.resolve(jobId + ".docx"));
            }
            // 2. ОБЯЗАТЕЛЬНО ОЧИЩАЕМ MDC, чтобы ID не "прилип" к другой задаче в этом
            // потоке
            MDC.remove(MDC_JOB_ID_KEY);
        }
    }

    private void processGenerateTask(String jobId, DocumentEntity entity) throws Exception {
        // ... (Твоя логика генерации остается без изменений)
        entity.updateProgress(10, JobStatus.PROCESSING);
        documentRepository.save(entity);

        String inputJson = redisTemplate.opsForValue().get("job:" + jobId + ":generate_input");
        if (inputJson == null)
            throw new RuntimeException("Missing GenerateDocumentRequest input JSON in Redis");

        GenerateDocumentRequest request = objectMapper.readValue(inputJson, GenerateDocumentRequest.class);
        redisTemplate.delete("job:" + jobId + ":generate_input"); // Защита от утечки памяти

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
                log.warn("Шаблон {} не найден, генерируем с нуля", request.getTemplateId());
                generatedFile = generatorService.generateDocument(request.getMetadata(), jobId, null);
            }
        } else {
            generatedFile = generatorService.generateDocument(request.getMetadata(), jobId, null);
        }

        Path targetPath = tempStorage.resolve(jobId + ".docx");
        Files.copy(generatedFile.toPath(), targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        generatedFile.delete();

        entity.updateProgress(100, JobStatus.SUCCESS);
        documentRepository.save(entity);

        log.info("✅ Генерация завершена. Файл сохранен");

        webhookService.sendWebhook(entity.getWebhookUrl(), jobId, "SUCCESS", "Generation complete",
                "/api/v1/documents/" + jobId + "/download");
    }

    private void processParseTask(String jobId, String originalName, DocumentEntity entity, Duration ttl)
            throws Exception {
        Path filePath = tempStorage.resolve(jobId + ".docx");
        if (!filePath.toFile().exists()) {
            log.error("❌ Файл не найден на диске");
            saveError(jobId, originalName, "File not found on disk", ttl);
            return;
        }

        // 3. ПОДКЛЮЧАЕМ STOPWATCH для профилирования производительности
        StopWatch watch = new StopWatch("Пайплайн обработки: " + originalName);

        entity.updateProgress(10, JobStatus.PROCESSING);
        documentRepository.save(entity);

        // --- Этап 1: Парсинг ---
        watch.start("1. Извлечение (Apache POI)");
        DocumentMetadataResponse rawResponse = parserService.parseDocument(filePath.toFile(), originalName, jobId);
        watch.stop();

        entity.updateProgress(50, JobStatus.PROCESSING);
        documentRepository.save(entity);

        // --- Этап 2: ИИ Коррекция ---
        watch.start("2. AI-Коррекция (Qwen)");
        log.info("🤖 Запуск AI-проверки и самокоррекции...");
        DocumentMetadataResponse cleanResponse = selfCorrectionService.validateAndCorrect(rawResponse);
        watch.stop();

        entity.updateProgress(70, JobStatus.PROCESSING);
        documentRepository.save(entity);

        String jsonResult = objectMapper.writeValueAsString(cleanResponse);
        redisTemplate.opsForValue().set("job:" + jobId, jsonResult, ttl);
        entity.setResultJson(jsonResult);

        entity.updateProgress(90, JobStatus.PROCESSING);
        documentRepository.save(entity);

        // --- Этап 3: RAG Векторизация ---
        watch.start("3. Векторизация (AllMiniLM)");
        log.info("🧠 Запуск векторизации для RAG...");
        vectorizationService.vectorizeAndStore(cleanResponse, jobId);
        watch.stop();

        entity.updateProgress(100, JobStatus.SUCCESS);
        documentRepository.save(entity);

        // Выводим красивый отчет по времени
        log.info("✅ Парсинг успешно завершен!\n{}", watch.prettyPrint());

        webhookService.sendWebhook(entity.getWebhookUrl(), jobId, "SUCCESS", "Parsing and Vectorization complete",
                "/api/v1/documents/" + jobId);
    }

    private void saveError(String jobId, String originalName, String message, Duration ttl) {
        try {
            Map<String, String> errorMap = Map.of(
                    "status", JobStatus.ERROR.name(),
                    "jobId", jobId,
                    "message", message != null ? message : "Unknown error");
            String errorJson = objectMapper.writeValueAsString(errorMap);
            redisTemplate.opsForValue().set("job:" + jobId, errorJson, ttl);
        } catch (Exception redisEx) {
            log.error("Failed to write error to Redis. jobId={}", jobId, redisEx);
        }
        DocumentEntity err = new DocumentEntity(jobId, JobStatus.ERROR, originalName, "PARSE", null);
        err.setResultJson(message != null ? message : "Unknown error");
        documentRepository.save(err);
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("⚠️ Не удалось удалить временный файл", e);
        }
    }
}