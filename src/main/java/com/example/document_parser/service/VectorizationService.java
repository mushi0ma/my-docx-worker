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

import java.util.ArrayList;
import java.util.List;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * Векторизация документов для RAG-чата.
 *
 * ИСПРАВЛЕНО: @Async теперь явно указывает "vectorizationExecutor"
 * вместо дефолтного пула. Без явного имени Spring использует
 * SimpleAsyncTaskExecutor (создаёт неограниченные потоки).
 */
@Service
public class VectorizationService {

    private static final Logger log = LoggerFactory.getLogger(VectorizationService.class);

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

    @Async("vectorizationExecutor")
    public void vectorizeAndStore(com.example.document_parser.dto.DocumentMetadataResponse metadata, String jobId) {
        log.info("Starting vectorization. jobId={}", jobId);

        if (isAlreadyIndexed(jobId)) {
            log.info("Document already indexed, skipping. jobId={}", jobId);
            return;
        }

        List<String> textChunks = chunkingService.chunkDocument(metadata);
        if (textChunks.isEmpty()) {
            log.warn("No text chunks generated. jobId={}", jobId);
            return;
        }

        String fileName = metadata.getFileName() != null ? metadata.getFileName() : "unknown";
        List<TextSegment> segments = new ArrayList<>();
        for (int i = 0; i < textChunks.size(); i++) {
            segments.add(TextSegment.from(
                    textChunks.get(i),
                    Metadata.from("jobId", jobId)
                            .put("fileName", fileName)
                            .put("chunkIndex", String.valueOf(i))
                            .put("totalChunks", String.valueOf(textChunks.size()))));
        }

        try {
            int totalStored = 0;
            for (int i = 0; i < segments.size(); i += EMBEDDING_BATCH_SIZE) {
                int end = Math.min(i + EMBEDDING_BATCH_SIZE, segments.size());
                List<TextSegment> batch = segments.subList(i, end);

                List<Embedding> embeddings = embeddingModel.embedAll(batch).content();
                addWithRetry(embeddings, batch, jobId);

                totalStored += embeddings.size();
                log.debug("Vectorized batch {}/{}. jobId={}", end, segments.size(), jobId);
            }
            log.info("Vectorization complete. chunks={}, jobId={}", totalStored, jobId);

        } catch (Exception e) {
            log.error("Vectorization failed (non-critical, RAG won't work). jobId={}, error={}",
                    jobId, e.getMessage(), e);
        }
    }

    private boolean isAlreadyIndexed(String jobId) {
        try {
            float[] zeroVector = new float[384];
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(Embedding.from(zeroVector))
                    .maxResults(1)
                    .filter(metadataKey("jobId").isEqualTo(jobId))
                    .build();
            return !embeddingStore.search(request).matches().isEmpty();
        } catch (Exception e) {
            log.warn("Could not check indexing status, will re-index. jobId={}", jobId);
            return false;
        }
    }

    /**
     * Retry wrapper for PgVector batch insert.
     * PostgreSQL JDBC driver can throw "prepared statement S_1 already exists"
     * when multiple connections reuse cached prepared statements concurrently.
     * Retrying with a small delay usually resolves this.
     */
    private void addWithRetry(List<Embedding> embeddings, List<TextSegment> segments, String jobId) {
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                embeddingStore.addAll(embeddings, segments);
                return; // success
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.contains("prepared statement") && msg.contains("already exists") && attempt < maxRetries) {
                    log.warn("PgVector prepared statement conflict (attempt {}/{}), retrying in 500ms. jobId={}",
                            attempt, maxRetries, jobId);
                    try {
                        Thread.sleep(500L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Vectorization interrupted", ie);
                    }
                } else if (attempt < maxRetries) {
                    log.warn("Vectorization batch failed (attempt {}/{}), retrying. jobId={}, error={}",
                            attempt, maxRetries, jobId, msg);
                    try {
                        Thread.sleep(300L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Vectorization interrupted", ie);
                    }
                } else {
                    // Final attempt: try inserting one by one
                    log.warn("Batch insert failed after {} retries, trying one-by-one. jobId={}", maxRetries, jobId);
                    for (int i = 0; i < embeddings.size(); i++) {
                        try {
                            embeddingStore.add(embeddings.get(i), segments.get(i));
                        } catch (Exception inner) {
                            log.debug("Single insert failed for chunk {}, skipping. jobId={}", i, jobId);
                        }
                    }
                }
            }
        }
    }
}