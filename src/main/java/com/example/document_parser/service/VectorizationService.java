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
                            .put("totalChunks", String.valueOf(textChunks.size()))
            ));
        }

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
}