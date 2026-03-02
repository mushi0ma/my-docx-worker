package com.example.document_parser.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Service
public class RagChatService {

    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final StreamingChatLanguageModel chatModel;

    public RagChatService(EmbeddingStore<TextSegment> embeddingStore,
                          EmbeddingModel embeddingModel,
                          @Qualifier("streamingChatModel") StreamingChatLanguageModel chatModel) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
    }

    public SseEmitter chatWithDocument(String jobId, String userQuestion) {
        SseEmitter emitter = new SseEmitter(120000L);

        try {
            log.info("🔍 Ищем контекст для вопроса: '{}' (Job ID: {})", userQuestion, jobId);

            // 1. Превращаем вопрос пользователя в вектор
            Embedding questionEmbedding = embeddingModel.embed(userQuestion).content();

            // 2. Ищем похожие куски в базе (Строго фильтруем по конкретному документу!)
            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(questionEmbedding)
                    .maxResults(5) // Берем 5 самых релевантных абзацев
                    .filter(metadataKey("jobId").isEqualTo(jobId))
                    .build();

            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);

            // 3. Собираем найденный текст в единый контекст
            String context = searchResult.matches().stream()
                    .map(match -> match.embedded().text())
                    .collect(Collectors.joining("\n\n---\n\n"));

            log.info("🧠 Найдено фрагментов для контекста: {}", searchResult.matches().size());

            // 4. Формируем системный промпт (Prompt Engineering)
            String prompt = "Ты — умный AI-ассистент. Ответь на вопрос пользователя, опираясь ТОЛЬКО на предоставленный контекст из документа. " +
                    "Если ответа в контексте нет, честно скажи об этом. Отвечай на языке запроса.\n\n" +
                    "КОНТЕКСТ ДОКУМЕНТА:\n" + context + "\n\n" +
                    "ВОПРОС ПОЛЬЗОВАТЕЛЯ:\n" + userQuestion;

            // 5. Стримим ответ
            chatModel.generate(prompt, new StreamingResponseHandler<AiMessage>() {
                @Override
                public void onNext(String token) {
                    try { emitter.send(token); } catch (IOException e) { emitter.completeWithError(e); }
                }
                @Override
                public void onComplete(Response<AiMessage> response) {
                    emitter.complete();
                }
                @Override
                public void onError(Throwable error) {
                    emitter.completeWithError(error);
                }
            });

        } catch (Exception e) {
            log.error("RAG Error: {}", e.getMessage(), e);
            emitter.completeWithError(e);
        }

        return emitter;
    }
}