package com.example.document_parser.controller;

import com.example.document_parser.config.StorageConfig;
import com.example.document_parser.dto.DocumentMetadataResponse;
import com.example.document_parser.dto.GenerateDocumentRequest;
import com.example.document_parser.entity.DocumentEntity;
import com.example.document_parser.exception.AppExceptions;
import com.example.document_parser.export.DocumentExporter;
import com.example.document_parser.model.JobStatus;
import com.example.document_parser.repository.DocumentRepository;
import com.example.document_parser.service.AiDocumentService;
import com.example.document_parser.service.DocumentProducer;
import com.example.document_parser.service.DocxGeneratorService;
import com.example.document_parser.service.MarkdownService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Document Parser", description = "API для асинхронного парсинга и экспорта DOCX")
public class DocumentController {

    private final DocumentProducer documentProducer;
    private final StringRedisTemplate redisTemplate;
    private final DocumentRepository documentRepository;
    private final ObjectMapper objectMapper;
    private final DocxGeneratorService docxGeneratorService; // Теперь инициализируется!
    private final Path tempStorage;
    private final Map<String, DocumentExporter> exporters;
    private final AiDocumentService aiDocumentService;
    private final MarkdownService markdownService;

    // ИСПРАВЛЕНИЕ 1: Добавили DocxGeneratorService в параметры конструктора
    public DocumentController(DocumentProducer documentProducer,
                              StringRedisTemplate redisTemplate,
                              DocumentRepository documentRepository,
                              StorageConfig storageConfig,
                              ObjectMapper objectMapper,
                              List<DocumentExporter> exporterList,
                              DocxGeneratorService docxGeneratorService,
                              AiDocumentService aiDocumentService,
                              MarkdownService markdownService) {
        this.documentProducer = documentProducer;
        this.redisTemplate = redisTemplate;
        this.documentRepository = documentRepository;
        this.tempStorage = storageConfig.getTempStoragePath();
        this.objectMapper = objectMapper;
        this.docxGeneratorService = docxGeneratorService; // Инициализация
        this.aiDocumentService = aiDocumentService;
        this.markdownService = markdownService;
        this.exporters = exporterList.stream()
                .collect(Collectors.toMap(DocumentExporter::format, Function.identity()));
    }

    @PostMapping(value = "/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Загрузить DOCX файл для фоновой обработки")
    public ResponseEntity<?> parseWordDocument(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty() || file.getOriginalFilename() == null || !file.getOriginalFilename().endsWith(".docx")) {
            throw new IllegalArgumentException("Пожалуйста, загрузите файл формата .docx");
        }

        String jobId = UUID.randomUUID().toString();
        try {
            Path filePath = tempStorage.resolve(jobId + ".docx");
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

            documentRepository.save(new DocumentEntity(jobId, JobStatus.PENDING, null));
            documentProducer.sendToQueue(jobId);

            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "message", "Файл принят",
                    "jobId", jobId,
                    "statusUrl", "/api/v1/documents/" + jobId + "/status"));
        } catch (Exception e) {
            throw new RuntimeException("Ошибка регистрации задачи", e);
        }
    }

    @PostMapping(value = "/generate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Сгенерировать DOCX файл асинхронно")
    public ResponseEntity<?> generateDocxAsync(@RequestBody GenerateDocumentRequest request) {
        String jobId = UUID.randomUUID().toString();
        try {
            String jsonRequest = objectMapper.writeValueAsString(request);
            redisTemplate.opsForValue().set("job:" + jobId + ":generate_input", jsonRequest,
                    java.time.Duration.ofHours(24));

            documentRepository.save(new DocumentEntity(jobId, JobStatus.PENDING, "generation_" + jobId + ".docx",
                    "GENERATE", request.getWebhookUrl()));
            documentProducer.sendToQueue(jobId, "GENERATE");

            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "message", "Задача на генерацию принята",
                    "jobId", jobId,
                    "statusUrl", "/api/v1/documents/" + jobId + "/status"));
        } catch (Exception e) {
            throw new RuntimeException("Ошибка регистрации задачи генерации", e);
        }
    }

    @GetMapping(value = "/{jobId}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Узнать статус задачи (PENDING, SUCCESS, ERROR)")
    public ResponseEntity<?> getStatus(@PathVariable String jobId) {
        validateJobId(jobId);
        DocumentEntity entity = documentRepository.findById(jobId)
                .orElseThrow(() -> new AppExceptions.JobNotFoundException(jobId));
        return ResponseEntity.ok(Map.of("jobId", jobId, "status", entity.getStatus().name()));
    }

    @GetMapping(value = "/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Получить оригинальный JSON-ответ парсера")
    public ResponseEntity<String> getResult(@PathVariable String jobId) {
        return ResponseEntity.ok(fetchResultString(jobId));
    }

    @GetMapping(value = "/{jobId}/export/{format}")
    @Operation(summary = "Экспорт в форматы tsv, jsonl или markdown")
    public ResponseEntity<StreamingResponseBody> exportDocument(@PathVariable String jobId,
                                                                @PathVariable String format) {
        DocumentExporter exporter = exporters.get(format.toLowerCase());
        if (exporter == null)
            throw new IllegalArgumentException("Unsupported format: " + format);

        DocumentMetadataResponse response = fetchAndDeserialize(jobId);

        StreamingResponseBody stream = out -> {
            try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                exporter.export(response, jobId, writer);
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, exporter.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"document_" + jobId + exporter.fileExtension() + "\"")
                .body(stream);
    }

    @GetMapping(value = "/{jobId}/download", produces = "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    @Operation(summary = "Скачать асинхронно сгенерированный DOCX файл")
    public ResponseEntity<byte[]> downloadGeneratedDocx(@PathVariable String jobId) {
        validateJobId(jobId);
        DocumentEntity entity = documentRepository.findById(jobId)
                .orElseThrow(() -> new AppExceptions.JobNotFoundException(jobId));

        if (entity.getStatus() != JobStatus.SUCCESS) {
            throw new RuntimeException("Генерация еще не завершена: " + entity.getStatus());
        }

        try {
            Path filePath = tempStorage.resolve(jobId + ".docx");
            if (!Files.exists(filePath)) {
                throw new AppExceptions.JobNotFoundException("Сгенерированный файл не найден на диске");
            }
            byte[] bytes = Files.readAllBytes(filePath);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"reconstructed_" + jobId + ".docx\"")
                    .body(bytes);
        } catch (IOException e) {
            throw new AppExceptions.JobFailedException(jobId, "Ошибка чтения файла: " + e.getMessage());
        }
    }

    @GetMapping(value = "/{jobId}/generate", produces = "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    @Operation(summary = "Сгенерировать DOCX файл из извлеченных JSON-метаданных (синхронно)")
    public ResponseEntity<byte[]> generateDocx(@PathVariable String jobId) {
        DocumentMetadataResponse response = fetchAndDeserialize(jobId);
        try {
            java.io.File generatedFile = docxGeneratorService.generateDocument(response, jobId);
            byte[] bytes = Files.readAllBytes(generatedFile.toPath());
            generatedFile.delete();

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"reconstructed_" + jobId + ".docx\"")
                    .body(bytes);
        } catch (Exception e) {
            throw new AppExceptions.JobFailedException(jobId, "Ошибка при генерации DOCX: " + e.getMessage());
        }
    }

    @GetMapping(value = "/{jobId}/images/{imageName}")
    @Operation(summary = "Получить картинку по имени файла")
    public ResponseEntity<byte[]> getImage(@PathVariable String jobId, @PathVariable String imageName) {
        validateJobId(jobId);
        try {
            String shard = jobId.replace("-", "").substring(0, 4);
            Path imagePath = tempStorage.resolve("images").resolve(shard.substring(0, 2)).resolve(shard.substring(2, 4))
                    .resolve(jobId).resolve(imageName);
            if (Files.exists(imagePath)) {
                return ResponseEntity.ok().contentType(guessMediaType(imageName)).body(Files.readAllBytes(imagePath));
            }
            throw new AppExceptions.JobNotFoundException("Image not found: " + imageName);
        } catch (IOException e) {
            throw new RuntimeException("Error reading image", e);
        }
    }

    @Operation(summary = "Получить интеллектуальную выжимку документа (AI Summary)")
    @GetMapping(value = "/{jobId}/ai/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getAiSummary(@PathVariable String jobId) {
        // ИСПРАВЛЕНИЕ 2: используем fetchResultString(jobId)
        String resultJson = fetchResultString(jobId);
        try {
            DocumentMetadataResponse doc = objectMapper.readValue(resultJson, DocumentMetadataResponse.class);
            String markdown = markdownService.toMarkdown(doc);
            String aiResponse = aiDocumentService.generateSummary(markdown);
            return ResponseEntity.ok(aiResponse);
        } catch (Exception e) {
            throw new RuntimeException("Ошибка при вызове ИИ: " + e.getMessage(), e);
        }
    }

    // Вспомогательные методы
    private DocumentMetadataResponse fetchAndDeserialize(String jobId) {
        String json = fetchResultString(jobId);
        try {
            return objectMapper.readValue(json, DocumentMetadataResponse.class);
        } catch (Exception e) {
            throw new AppExceptions.JobFailedException(jobId, "Failed to parse JSON result");
        }
    }

    private String fetchResultString(String jobId) {
        validateJobId(jobId);
        String result = redisTemplate.opsForValue().get("job:" + jobId);
        if (result == null) {
            Optional<DocumentEntity> entityOpt = documentRepository.findById(jobId);
            if (entityOpt.isEmpty() || entityOpt.get().getStatus() != JobStatus.SUCCESS) {
                throw new AppExceptions.JobNotFoundException(jobId);
            }
            result = entityOpt.get().getResultJson();
            redisTemplate.opsForValue().set("job:" + jobId, result, java.time.Duration.ofHours(24));
        }
        checkForError(jobId, result);
        return result;
    }

    private void checkForError(String jobId, String json) {
        try {
            var node = objectMapper.readTree(json);
            if (node.has("status") && "ERROR".equals(node.get("status").asText())) {
                String msg = node.has("message") ? node.get("message").asText() : "unknown";
                throw new AppExceptions.JobFailedException(jobId, msg);
            }
        } catch (AppExceptions.JobFailedException e) {
            throw e;
        } catch (Exception ignored) {
        }
    }

    private void validateJobId(String jobId) {
        if (jobId == null || !jobId.matches("[a-f0-9\\-]{36}"))
            throw new AppExceptions.InvalidJobIdException(jobId);
    }

    private MediaType guessMediaType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png"))
            return MediaType.IMAGE_PNG;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg"))
            return MediaType.IMAGE_JPEG;
        if (lower.endsWith(".gif"))
            return MediaType.IMAGE_GIF;
        if (lower.endsWith(".webp"))
            return MediaType.parseMediaType("image/webp");
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}