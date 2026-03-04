package com.example.document_parser.service;

import com.example.document_parser.dto.DocumentMetadataResponse.DocumentBlock;
import com.example.document_parser.service.ai.AiPrompts;
import com.example.document_parser.service.ai.DocumentDraftingAiService;
import com.example.document_parser.service.ai.MultiAgentDocumentService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Агент для написания и верстки новых Word-документов с нуля на основе промпта.
 *
 * Стратегия генерации (приоритет):
 * 1. Multi-Agent Chain (Planning → Writing → Formatting) — лучшее качество
 * 2. LangChain4j AI Service (structured output) — fallback #1
 * 3. Manual generate() + JSON parsing — fallback #2
 *
 * Task 9: Перед генерацией ищет похожие документы в RAG для стилевого
 * референса.
 */
@Service
public class DocumentDraftingAgent {

    private static final Logger log = LoggerFactory.getLogger(DocumentDraftingAgent.class);

    private final ChatLanguageModel writerModel;
    private final DynamicDocxBuilderService docxBuilder;
    private final ObjectMapper objectMapper;
    private final MultiAgentDocumentService multiAgentService;
    private final DocumentDraftingAiService aiService; // nullable
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    public DocumentDraftingAgent(
            @Qualifier("writerModel") ChatLanguageModel writerModel,
            DynamicDocxBuilderService docxBuilder,
            ObjectMapper objectMapper,
            MultiAgentDocumentService multiAgentService,
            @org.springframework.lang.Nullable DocumentDraftingAiService aiService,
            EmbeddingStore<TextSegment> embeddingStore,
            EmbeddingModel embeddingModel) {
        this.writerModel = writerModel;
        this.docxBuilder = docxBuilder;
        this.objectMapper = objectMapper;
        this.multiAgentService = multiAgentService;
        this.aiService = aiService;
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
    }

    public File draftNewDocument(String userPrompt, String jobId) throws Exception {
        log.info("🤖 Агент-Писатель начал работу над задачей: {}", jobId);

        // =====================================================
        // Task 9: RAG — ищем похожие документы как референс
        // =====================================================
        String ragContext = retrieveRagContext(userPrompt);
        String enrichedPrompt = userPrompt;
        if (!ragContext.isBlank()) {
            enrichedPrompt = userPrompt
                    + "\n\nИспользуй следующие существующие документы как референс стиля и структуры:\n" + ragContext;
            log.info("📚 RAG нашёл похожие документы, обогащаем промпт.");
        }

        // =====================================================
        // Strategy 1: Multi-Agent Chain (best quality)
        // =====================================================
        List<DocumentBlock> blocks = null;
        try {
            log.info("🔗 Попытка генерации через Multi-Agent Chain...");
            blocks = multiAgentService.generateWithAgentChain(enrichedPrompt, ragContext);
            log.info("✅ Multi-Agent Chain успешно вернул {} блоков", blocks.size());
        } catch (Exception e) {
            log.warn("⚠️ Multi-Agent Chain failed: {}. Trying fallback...", e.getMessage());
        }

        // =====================================================
        // Strategy 2: LangChain4j AI Service (structured output)
        // =====================================================
        if (blocks == null && aiService != null) {
            try {
                log.info("🧩 Попытка генерации через LangChain4j AI Service...");
                blocks = aiService.generateDocument(enrichedPrompt);
                log.info("✅ AI Service успешно вернул {} блоков", blocks.size());
            } catch (Exception e) {
                log.warn("⚠️ AI Service failed: {}. Trying manual fallback...", e.getMessage());
            }
        }

        // =====================================================
        // Strategy 3: Manual generate + JSON parse (legacy)
        // =====================================================
        if (blocks == null) {
            log.info("📝 Используем fallback: ручной generate() + JSON...");

            if (writerModel == null) {
                throw new IllegalStateException("Модель не настроена. Проверь GEMINI_API_KEY или GROQ_API_KEY в .env");
            }

            String fullPrompt = AiPrompts.draftDocument(enrichedPrompt);
            log.info("⏳ Ожидание ответа от модели...");
            String jsonResponse = writerModel.generate(fullPrompt);
            log.info("✅ Модель сгенерировала ответ, парсим JSON...");

            jsonResponse = stripMarkdownFences(jsonResponse);
            blocks = objectMapper.readValue(jsonResponse, new TypeReference<>() {
            });
        }

        log.info("🛠 Передаем {} блоков в DynamicDocxBuilder для сборки...", blocks.size());
        return docxBuilder.buildDocumentFromBlocks(blocks, jobId);
    }

    /**
     * Task 9: RAG — поиск похожих документов в векторной базе.
     */
    private String retrieveRagContext(String prompt) {
        try {
            Embedding queryEmbedding = embeddingModel.embed(prompt).content();
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(3)
                    .build();

            EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);

            if (result.matches().isEmpty()) {
                log.debug("RAG: no similar documents found");
                return "";
            }

            String context = result.matches().stream()
                    .filter(m -> m.score() > 0.5) // Only reasonably similar docs
                    .map(m -> m.embedded().text())
                    .collect(Collectors.joining("\n\n---\n\n"));

            log.info("RAG: found {} relevant chunks for context", result.matches().size());
            return context.length() > 5000 ? context.substring(0, 5000) + "\n...[truncated]" : context;
        } catch (Exception e) {
            log.debug("RAG retrieval failed (not critical): {}", e.getMessage());
            return "";
        }
    }

    private String stripMarkdownFences(String json) {
        if (json == null)
            return "[]";
        String t = json.trim();
        if (t.startsWith("```json"))
            t = t.substring(7);
        else if (t.startsWith("```"))
            t = t.substring(3);
        if (t.endsWith("```"))
            t = t.substring(0, t.length() - 3);
        return t.trim();
    }
}