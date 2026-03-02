package com.example.document_parser.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@Service
public class AiDocumentService {
    private static final Logger log = LoggerFactory.getLogger(AiDocumentService.class);

    private final StreamingChatLanguageModel streamingRouterModel;
    private final ChatLanguageModel routerModel;

    // Внедряем СРАЗУ ДВЕ модели: для стриминга и для обычных ответов
    public AiDocumentService(
            @Qualifier("streamingRouterModel") StreamingChatLanguageModel streamingRouterModel,
            @Qualifier("routerModel") ChatLanguageModel routerModel) {
        this.streamingRouterModel = streamingRouterModel;
        this.routerModel = routerModel;
    }

    // 1. СТАРЫЙ МЕТОД: Обычный JSON-ответ (Исправляет ошибку "Cannot resolve method")
    public String generateSummary(String markdownContent) {
        log.info("🤖 Запуск AI-анализа документа через Groq (Sync)...");
        int maxLength = 25000;
        String safeContent = markdownContent.length() > maxLength
                ? markdownContent.substring(0, maxLength) + "\n\n...[ТЕКСТ ОБРЕЗАН]..."
                : markdownContent;

        String prompt = "Ты - IT-аналитик. Проанализируй этот документ и верни ответ СТРОГО в формате JSON без markdown оберток:\n" +
                "{\n" +
                "  \"documentType\": \"(Конспект/СӨЖ/Лабораторная/Отчет/Договор)\",\n" +
                "  \"language\": \"(kk/ru/en)\",\n" +
                "  \"summary\": \"(Краткая суть в 2 предложениях)\",\n" +
                "  \"technologies\": [\"React\", \"JS\", ...]\n" +
                "}\n\nДокумент:\n" + safeContent;

        return routerModel.generate(prompt);
    }

    // 2. НОВЫЙ МЕТОД: Стриминг (По буквам)
    public SseEmitter generateSummaryStream(String markdownContent) {
        log.info("🤖 Запуск AI-стриминга через Groq...");
        SseEmitter emitter = new SseEmitter(120000L);

        int maxLength = 25000;
        String safeContent = markdownContent.length() > maxLength
                ? markdownContent.substring(0, maxLength) + "\n\n...[ТЕКСТ ОБРЕЗАН]..."
                : markdownContent;

        String prompt = "Ты - IT-аналитик. Напиши красивое Markdown-резюме для этого документа. " +
                "Укажи тип документа, язык, стек технологий и краткую суть.\n\nДокумент:\n" + safeContent;

        streamingRouterModel.generate(prompt, new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String token) {
                try { emitter.send(token); } catch (IOException e) { emitter.completeWithError(e); }
            }
            @Override
            public void onComplete(Response<AiMessage> response) { emitter.complete(); }
            @Override
            public void onError(Throwable error) { emitter.completeWithError(error); }
        });

        return emitter;
    }
}