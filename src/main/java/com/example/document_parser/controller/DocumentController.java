package com.example.document_parser.controller;

import com.example.document_parser.service.DocumentProducer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentProducer documentProducer;
    private final StringRedisTemplate redisTemplate; // Подключаем Redis
    private final Path tempStorage = Paths.get("/home/mushi/temp_docs");

    // Не забудь добавить redisTemplate в конструктор!
    public DocumentController(DocumentProducer documentProducer, StringRedisTemplate redisTemplate) {
        this.documentProducer = documentProducer;
        this.redisTemplate = redisTemplate;
        try {
            Files.createDirectories(tempStorage);
        } catch (IOException e) {
            throw new RuntimeException("Не удалось создать директорию для временного хранения файлов", e);
        }
    }

    @PostMapping("/parse")
    public ResponseEntity<String> parseWordDocument(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty() || !file.getOriginalFilename().endsWith(".docx")) {
            return ResponseEntity.badRequest().body("Пожалуйста, загрузите файл формата .docx");
        }

        String jobId = UUID.randomUUID().toString();

        try {
            Path filePath = tempStorage.resolve(jobId + ".docx");
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
            documentProducer.sendToQueue(jobId);

            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body("Файл принят в обработку. ID задачи: " + jobId);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Произошла ошибка при регистрации задачи: " + e.getMessage());
        }
    }

    // НОВЫЙ ЭНДПОИНТ: Получение результата по ID задачи
    @GetMapping(value = "/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getResult(@PathVariable String jobId) {
        // Ищем результат в Redis по ключу "job:твой-id"
        String result = redisTemplate.opsForValue().get("job:" + jobId);

        if (result == null) {
            // Если результата еще нет, значит задача в очереди или ID неверный
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("{\"status\": \"PENDING_OR_NOT_FOUND\", \"message\": \"Задача еще выполняется или не существует\"}");
        }

        if (result.contains("\"status\":\"ERROR\"")) {
            // Если парсер упал с ошибкой (мы перехватили её в Consumer)
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }

        // Успех! Отдаем готовый JSON
        return ResponseEntity.ok(result);
    }
}