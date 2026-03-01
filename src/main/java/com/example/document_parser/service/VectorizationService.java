package com.example.document_parser.service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class VectorizationService {

    private static final Logger log = LoggerFactory.getLogger(VectorizationService.class);

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

    public void vectorizeAndStore(com.example.document_parser.dto.DocumentMetadataResponse metadata, String jobId) {
        log.info("Starting vectorization for job {}", jobId);

        // 1. Chunk document
        List<String> textChunks = chunkingService.chunkDocument(metadata);

        // 2. Wrap into TextSegments with metadata
        List<TextSegment> segments = textChunks.stream()
                .map(chunk -> TextSegment.from(chunk,
                        Metadata.from("jobId", jobId).put("fileName",
                                metadata.getFileName() != null ? metadata.getFileName() : "unknown")))
                .collect(Collectors.toList());

        if (segments.isEmpty()) {
            log.warn("No text segments generated for job {}", jobId);
            return;
        }

        // 3. Generate Embeddings (calls nomic-embed-text or configured model)
        try {
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

            // 4. Save to PgVector (Supabase)
            embeddingStore.addAll(embeddings, segments);

            log.info("Successfully stored {} vectorized chunks in PgVector for job {}", embeddings.size(), jobId);
        } catch (Exception e) {
            log.error("Failed to vectorize or store document chunks. Error: {}", e.getMessage(), e);
        }
    }
}
