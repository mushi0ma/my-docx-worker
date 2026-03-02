package com.example.document_parser.service;

import com.example.document_parser.service.ai.AiPrompts;
import com.example.document_parser.service.ai.SseEmitterFactory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI-сервис для анализа и суммаризации документов.
 * Модель: Llama 4 Scout (Groq) — >460 токенов/сек, MoE 17B/109B.
 */
@Service
public class AiDocumentService {

    private static final Logger log = LoggerFactory.getLogger(AiDocumentService.class);

    @Value("${app.ai.summary.max-chars:25000}")
    private int summaryMaxChars;

    private final StreamingChatLanguageModel streamingSummaryModel;
    private final ChatLanguageModel summaryModel;
    private final SseEmitterFactory sseEmitterFactory;

    public AiDocumentService(
            @Qualifier("streamingSummaryModel") StreamingChatLanguageModel streamingSummaryModel,
            @Qualifier("summaryModel") ChatLanguageModel summaryModel,
            SseEmitterFactory sseEmitterFactory) {
        this.streamingSummaryModel = streamingSummaryModel;
        this.summaryModel = summaryModel;
        this.sseEmitterFactory = sseEmitterFactory;
    }

    /** Синхронный JSON-анализ. GET /ai/summary */
    @Retry(name = "aiSummary", fallbackMethod = "summaryFallback")
    @CircuitBreaker(name = "aiSummary", fallbackMethod = "summaryFallback")
    public String generateSummary(String markdownContent) {
        log.info("Generating AI summary (sync)");
        return summaryModel.generate(AiPrompts.summaryJson(truncate(markdownContent)));
    }

    public String summaryFallback(String markdownContent, Throwable t) {
        log.error("AI summary generation or circuit broken: {}", t.getMessage());
        return "{\"error\": \"К сожалению, не удалось сгенерировать саммари из-за недоступности AI сервиса.\"}";
    }

    /** Стриминговый Markdown-анализ. GET /ai/summary/stream */
    public SseEmitter generateSummaryStream(String markdownContent) {
        log.info("Generating AI summary (streaming)");
        return sseEmitterFactory.stream(
                streamingSummaryModel,
                AiPrompts.summaryMarkdownStream(truncate(markdownContent)),
                "summary");
    }

    private String truncate(String content) {
        if (content == null)
            return "";
        if (content.length() <= summaryMaxChars)
            return content;
        return content.substring(0, summaryMaxChars)
                + "\n\n...[ТЕКСТ ОБРЕЗАН, показаны первые " + summaryMaxChars + " символов]...";
    }
}