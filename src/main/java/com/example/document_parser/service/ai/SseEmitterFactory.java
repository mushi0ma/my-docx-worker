package com.example.document_parser.service.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * Фабрика SSE-эмиттеров с поддержкой fallback-модели.
 *
 * Два метода:
 *   stream()             — простой стриминг, без fallback
 *   streamWithFallback() — при ошибке содержащей errorMarker переключается на резервную модель
 */
@Component
public class SseEmitterFactory {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterFactory.class);

    @Value("${app.ai.sse.timeout-ms:120000}")
    private long sseTimeoutMs;

    /**
     * Простой стриминг без fallback.
     * Используется для Summary где fallback не нужен (Groq стабилен).
     */
    public SseEmitter stream(StreamingChatLanguageModel model, String prompt, String context) {
        SseEmitter emitter = newEmitter(context);
        doStream(model, prompt, context, emitter, null, null);
        return emitter;
    }

    /**
     * Стриминг с автоматическим fallback.
     * При ошибке содержащей errorMarker (например "429") — повторяет запрос через fallback.
     * Пользователь не видит ошибку — получает ответ от резервной модели.
     */
    public SseEmitter streamWithFallback(StreamingChatLanguageModel primary,
                                         StreamingChatLanguageModel fallback,
                                         String prompt,
                                         String context,
                                         String errorMarker) {
        SseEmitter emitter = newEmitter(context);
        doStream(primary, prompt, context, emitter, fallback, errorMarker);
        return emitter;
    }

    private void doStream(StreamingChatLanguageModel model,
                          String prompt,
                          String context,
                          SseEmitter emitter,
                          StreamingChatLanguageModel fallback,
                          String errorMarker) {
        model.generate(prompt, new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String token) {
                try {
                    emitter.send(token);
                } catch (IOException e) {
                    // Клиент отключился — тихо завершаем
                    emitter.complete();
                }
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                emitter.complete();
            }

            @Override
            public void onError(Throwable error) {
                String errorMsg = error.getMessage() != null ? error.getMessage() : "";

                // Если ошибка содержит маркер (например "429") и есть fallback — переключаемся
                if (fallback != null && errorMarker != null && errorMsg.contains(errorMarker)) {
                    log.warn("Primary model rate limited, switching to fallback. context={}", context);
                    doStream(fallback, prompt, context, emitter, null, null);
                } else {
                    log.error("LLM streaming error. context={}, error={}", context, errorMsg);
                    emitter.completeWithError(error);
                }
            }
        });
    }

    private SseEmitter newEmitter(String context) {
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        emitter.onTimeout(() -> {
            log.debug("SSE timeout. context={}", context);
            emitter.complete();
        });
        emitter.onCompletion(() -> log.debug("SSE completed. context={}", context));
        emitter.onError(e -> log.warn("SSE error. context={}, error={}", context, e.getMessage()));
        return emitter;
    }
}