package com.example.document_parser.service;

import com.example.document_parser.service.ai.AiPrompts;
import com.example.document_parser.service.ai.SseEmitterFactory;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * RAG-чат: диалог с конкретным документом через векторный поиск.
 *
 * Стратегия моделей:
 * Основная: qwen3-next-80b-a3b:free (OpenRouter) — 262K контекст
 * Fallback: openrouter/free — автовыбор любой доступной бесплатной модели
 *
 * При 429 (rate limit) от основной модели автоматически переключается
 * на fallback без ошибки для пользователя.
 */
@Service
public class RagChatService {

    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);

    // Код 429 в сообщении об ошибке — признак rate limit
    private static final String RATE_LIMIT_MARKER = "429";

    @Value("${app.ai.rag.max-results:5}")
    private int ragMaxResults;

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final StreamingChatLanguageModel chatModel;
    private final StreamingChatLanguageModel chatModelFallback;
    private final SseEmitterFactory sseEmitterFactory;

    public RagChatService(EmbeddingStore<TextSegment> embeddingStore,
            EmbeddingModel embeddingModel,
            @Qualifier("streamingChatModel") StreamingChatLanguageModel chatModel,
            @Qualifier("streamingChatModelFallback") StreamingChatLanguageModel chatModelFallback,
            SseEmitterFactory sseEmitterFactory) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
        this.chatModelFallback = chatModelFallback;
        this.sseEmitterFactory = sseEmitterFactory;
    }

    /**
     * Стриминговый ответ на вопрос по документу.
     * При rate limit основной модели — тихо переключается на fallback.
     */
    @Retry(name = "ragChat")
    @CircuitBreaker(name = "ragChat")
    public SseEmitter chatWithDocument(String jobId, String userQuestion) {
        log.info("RAG chat started. jobId={}, questionLength={}", jobId, userQuestion.length());

        String context = retrieveContext(jobId, userQuestion);

        if ("Контекст по данному документу не найден.".equals(context)) {
            SseEmitter emitter = new SseEmitter();
            try {
                emitter.send(context);
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        String prompt = AiPrompts.ragChat(context, userQuestion);

        return sseEmitterFactory.streamWithFallback(
                chatModel,
                chatModelFallback,
                prompt,
                "rag-chat/" + jobId,
                RATE_LIMIT_MARKER);
    }

    private String retrieveContext(String jobId, String question) {
        Embedding questionEmbedding = embeddingModel.embed(question).content();

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(ragMaxResults)
                .filter(metadataKey("jobId").isEqualTo(jobId))
                .build();

        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
        log.debug("RAG retrieved {} chunks. jobId={}", result.matches().size(), jobId);

        if (result.matches().isEmpty()) {
            return "Контекст по данному документу не найден.";
        }

        return result.matches().stream()
                .map(match -> match.embedded().text())
                .collect(Collectors.joining("\n\n---\n\n"));
    }
}