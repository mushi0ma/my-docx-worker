package com.example.document_parser.service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * Исправления:
 * - Добавлена проверка идемпотентности: если документ уже проиндексирован — пропускаем
 *   (предотвращает дублирование при повторной обработке или ретрае)
 * - Добавлен batch-режим для больших документов (batches по N чанков)
 *   чтобы не превышать лимиты API embedding-модели
 * - Улучшен metadata: добавлено поле chunkIndex для отладки и фильтрации
 */
@Service
public class VectorizationService {

    private static final Logger log = LoggerFactory.getLogger(VectorizationService.class);

    // Размер батча для embedding — локальная модель может обрабатывать всё разом,
    // но внешние API часто имеют лимит на кол-во сегментов в одном запросе
    private static final int EMBEDDING_BATCH_SIZE = 50;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ChunkingService chunkingService;

    public VectorizationService(EmbeddingModel embeddingModel,
                                EmbeddingStore<TextSegment> embeddingStore,
                                ChunkingService chunkingService) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.chunkingService = chunkingService;
    }

    @Async
    public void vectorizeAndStore(com.example.document_parser.dto.DocumentMetadataResponse metadata, String jobId) {
        log.info("Starting vectorization. jobId={}", jobId);

        // --- Проверка идемпотентности ---
        // Если документ уже проиндексирован (напр. при повторном запросе),
        // не дублируем векторы в базе
        if (isAlreadyIndexed(jobId)) {
            log.info("Document already indexed, skipping. jobId={}", jobId);
            return;
        }

        // 1. Разбиваем документ на чанки
        List<String> textChunks = chunkingService.chunkDocument(metadata);

        if (textChunks.isEmpty()) {
            log.warn("No text chunks generated. jobId={}", jobId);
            return;
        }

        // 2. Оборачиваем в TextSegment с метаданными
        String fileName = metadata.getFileName() != null ? metadata.getFileName() : "unknown";
        List<TextSegment> segments = new java.util.ArrayList<>();
        for (int i = 0; i < textChunks.size(); i++) {
            segments.add(TextSegment.from(
                    textChunks.get(i),
                    Metadata.from("jobId", jobId)
                            .put("fileName", fileName)
                            .put("chunkIndex", String.valueOf(i))
                            .put("totalChunks", String.valueOf(textChunks.size()))
            ));
        }

        // 3. Генерируем эмбеддинги батчами
        try {
            int totalStored = 0;
            for (int i = 0; i < segments.size(); i += EMBEDDING_BATCH_SIZE) {
                int end = Math.min(i + EMBEDDING_BATCH_SIZE, segments.size());
                List<TextSegment> batch = segments.subList(i, end);

                List<Embedding> embeddings = embeddingModel.embedAll(batch).content();
                embeddingStore.addAll(embeddings, batch);

                totalStored += embeddings.size();
                log.debug("Vectorized batch {}/{}. jobId={}", end, segments.size(), jobId);
            }

            log.info("Vectorization complete. chunks={}, jobId={}", totalStored, jobId);

        } catch (Exception e) {
            // Не пробрасываем — векторизация асинхронная и не должна блокировать основной флоу
            // Документ будет доступен через обычный API, просто RAG не будет работать
            log.error("Vectorization failed. jobId={}, error={}", jobId, e.getMessage(), e);
        }
    }

    /**
     * Проверяет, проиндексирован ли документ, запросив хотя бы один вектор с его jobId.
     * Использует тот же EmbeddingStore, что и RAG-чат.
     */
    private boolean isAlreadyIndexed(String jobId) {
        try {
            // Делаем dummy-запрос с нулевым вектором только для проверки наличия записей
            // Используем минимальный вектор (384 измерения для all-minilm-l6-v2)
            float[] zeroVector = new float[384];
            Embedding dummyEmbedding = Embedding.from(zeroVector);

            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(dummyEmbedding)
                    .maxResults(1)
                    .filter(metadataKey("jobId").isEqualTo(jobId))
                    .build();

            return !embeddingStore.search(request).matches().isEmpty();

        } catch (Exception e) {
            // Если проверка упала — безопаснее проиндексировать заново
            log.warn("Could not check indexing status, will re-index. jobId={}", jobId);
            return false;
        }
    }
}