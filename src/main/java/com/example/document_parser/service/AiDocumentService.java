package com.example.document_parser.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class AiDocumentService {
    private static final Logger log = LoggerFactory.getLogger(AiDocumentService.class);
    private final ChatLanguageModel routerModel;

    public AiDocumentService(@Qualifier("routerModel") ChatLanguageModel routerModel) {
        this.routerModel = routerModel;
    }

    public String generateSummary(String markdownContent) {
        log.info("🤖 Запуск AI-анализа документа через Groq...");
        String prompt = "Ты - IT-аналитик. Проанализируй этот документ и верни ответ СТРОГО в формате JSON без markdown оберток (без ```json):\n" +
                "{\n" +
                "  \"documentType\": \"(Конспект/СӨЖ/Лабораторная/Отчет/Договор)\",\n" +
                "  \"language\": \"(kk/ru/en)\",\n" +
                "  \"summary\": \"(Краткая суть в 2 предложениях)\",\n" +
                "  \"technologies\": [\"React\", \"JS\", ...]\n" +
                "}\n\nДокумент:\n" + markdownContent;

        return routerModel.generate(prompt);
    }
}