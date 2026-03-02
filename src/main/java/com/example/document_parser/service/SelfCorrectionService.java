package com.example.document_parser.service;

import com.example.document_parser.dto.DocumentMetadataResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;

@Service
public class SelfCorrectionService {

    private static final Logger log = LoggerFactory.getLogger(SelfCorrectionService.class);

    private final DocumentValidator validator;
    private final DocumentCorrector corrector;
    private final ObjectMapper objectMapper;

    public SelfCorrectionService(
            @Qualifier("coderModel") ChatLanguageModel chatLanguageModel,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.validator = AiServices.create(DocumentValidator.class, chatLanguageModel);
        this.corrector = AiServices.create(DocumentCorrector.class, chatLanguageModel);
    }

    // ИСПРАВЛЕНИЕ: Переносим текст задания в @SystemMessage, а JSON передаем напрямую через @UserMessage в параметре
    interface DocumentValidator {
        @SystemMessage("You are an expert at validating JSON representations of documents. Your task is to check if the JSON has missing text, broken tables, or obvious OCR errors. Return true if IT IS VALID AND CLEAN. Return false if it is DIRTY, broken, or needs correction. You must reply strictly with a boolean: 'true' or 'false'. Here is the JSON to validate:")
        boolean isValid(@dev.langchain4j.service.UserMessage String json);
    }

    interface DocumentCorrector {
        @SystemMessage("You are a data cleaning expert. Your task is to fix broken JSON representations of parsed documents. Fix missing text, broken table structures, and bad formatting. Do NOT alter the schema. Reply ONLY with the valid JSON without markdown formatting. Here is the dirty JSON:")
        String correctDocument(@dev.langchain4j.service.UserMessage String json);
    }

    public DocumentMetadataResponse validateAndCorrect(DocumentMetadataResponse parsedDocument) {
        log.info("Starting Self-Correction workflow for document: {}", parsedDocument.getFileName());
        try {
            String json = objectMapper.writeValueAsString(parsedDocument);

            // Step 1: Validator Agent
            boolean clean = validator.isValid(json);
            log.info("Validator Agent returned status clean={}", clean);

            if (clean) {
                return parsedDocument;
            }

            // Step 2: Corrector Agent
            log.info("Document is dirty. Corrector Agent is processing the JSON...");
            String correctedJson = corrector.correctDocument(json);

            // Strip markdown JSON wrappers if the LLM added them
            if (correctedJson != null && correctedJson.trim().startsWith("```json")) {
                correctedJson = correctedJson.substring(correctedJson.indexOf('\n') + 1);
                if (correctedJson.endsWith("```")) {
                    correctedJson = correctedJson.substring(0, correctedJson.length() - 3);
                }
            }

            DocumentMetadataResponse correctedDoc = objectMapper.readValue(correctedJson,
                    DocumentMetadataResponse.class);
            log.info("Correction successful for document: {}", correctedDoc.getFileName());
            return correctedDoc;

        } catch (Exception e) {
            log.error("Failed Self-Correction Workflow. Returning original document. Error: {}", e.getMessage());
            return parsedDocument; // Fallback to original
        }
    }
}
