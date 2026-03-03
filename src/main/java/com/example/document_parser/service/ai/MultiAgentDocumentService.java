package com.example.document_parser.service.ai;

import com.example.document_parser.dto.DocumentMetadataResponse.DocumentBlock;
import com.example.document_parser.service.ai.AiPrompts;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-Agent Document Generation Service (Task 8).
 *
 * Разбивает генерацию документа на 3 этапа:
 * 1. Planning Agent — создаёт структуру (список заголовков секций)
 * 2. Writing Agent — пишет контент для каждой секции
 * 3. Formatting Agent — превращает текст в DocumentBlock JSON
 *
 * Каждый агент использует одну и ту же LLM с разными system prompts.
 */
@Service
public class MultiAgentDocumentService {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentDocumentService.class);

    private final ChatLanguageModel model;
    private final ObjectMapper objectMapper;

    public MultiAgentDocumentService(
            @Qualifier("advancedModel") ChatLanguageModel model,
            ObjectMapper objectMapper) {
        this.model = model;
        this.objectMapper = objectMapper;
    }

    /**
     * Выполняет полный 3-этапный пайплайн генерации документа.
     *
     * @param userPrompt запрос пользователя
     * @return готовый список DocumentBlock для передачи в DynamicDocxBuilderService
     */
    public List<DocumentBlock> generateWithAgentChain(String userPrompt) throws Exception {
        if (model == null) {
            throw new IllegalStateException("AI model (advancedModel) is not configured");
        }

        // =====================================================
        // STEP 1: Planning Agent — структура документа
        // =====================================================
        log.info("📋 [Planning Agent] Generating document structure...");
        String planPrompt = AiPrompts.planDocument(userPrompt);
        String planResponse = model.generate(planPrompt);
        planResponse = stripMarkdownFences(planResponse);

        List<String> sections;
        try {
            sections = objectMapper.readValue(planResponse, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("Planning Agent returned invalid JSON, using fallback structure: {}", e.getMessage());
            sections = List.of(userPrompt, "Введение", "Основная часть", "Заключение");
        }
        log.info("📋 [Planning Agent] Generated {} sections: {}", sections.size(), sections);

        // =====================================================
        // STEP 2: Writing Agent — контент каждой секции
        // =====================================================
        log.info("✍️ [Writing Agent] Writing content for {} sections...", sections.size());
        List<SectionContent> writtenSections = new ArrayList<>();
        StringBuilder previousContext = new StringBuilder();

        for (int i = 0; i < sections.size(); i++) {
            String sectionTitle = sections.get(i);
            log.info("✍️ [Writing Agent] Section {}/{}: {}", i + 1, sections.size(), sectionTitle);

            String writePrompt = AiPrompts.writeSection(sectionTitle, userPrompt, previousContext.toString());
            String content = model.generate(writePrompt);
            writtenSections.add(new SectionContent(sectionTitle, content));

            // Accumulate context for next sections (limit to ~3000 chars)
            previousContext.append(sectionTitle).append(": ").append(
                    content.length() > 500 ? content.substring(0, 500) + "..." : content).append("\n");

            if (previousContext.length() > 3000) {
                previousContext = new StringBuilder(previousContext.substring(previousContext.length() - 2000));
            }
        }

        // =====================================================
        // STEP 3: Formatting Agent — текст → DocumentBlock JSON
        // =====================================================
        log.info("🎨 [Formatting Agent] Converting {} sections to DocumentBlocks...", writtenSections.size());
        List<DocumentBlock> allBlocks = new ArrayList<>();

        for (int i = 0; i < writtenSections.size(); i++) {
            SectionContent sc = writtenSections.get(i);
            log.info("🎨 [Formatting Agent] Formatting section {}/{}: {}", i + 1, writtenSections.size(), sc.title());

            String formatPrompt = AiPrompts.formatToBlocks(sc.title(), sc.content(), i == 0);
            String blocksJson = model.generate(formatPrompt);
            blocksJson = stripMarkdownFences(blocksJson);

            try {
                List<DocumentBlock> sectionBlocks = objectMapper.readValue(
                        blocksJson, new TypeReference<>() {
                        });
                allBlocks.addAll(sectionBlocks);
            } catch (Exception e) {
                log.warn("Formatting Agent failed for section '{}': {}. Adding as plain text.", sc.title(),
                        e.getMessage());
                // Fallback: add as plain paragraph blocks
                allBlocks.add(DocumentBlock.builder()
                        .type("PARAGRAPH")
                        .alignment("LEFT")
                        .runs(List.of(
                                com.example.document_parser.dto.DocumentMetadataResponse.RunData.builder()
                                        .text(sc.title())
                                        .isBold(true)
                                        .fontSize(16.0)
                                        .color("1A237E")
                                        .build()))
                        .build());
                allBlocks.add(DocumentBlock.builder()
                        .type("PARAGRAPH")
                        .alignment("BOTH")
                        .runs(List.of(
                                com.example.document_parser.dto.DocumentMetadataResponse.RunData.builder()
                                        .text(sc.content())
                                        .fontSize(12.0)
                                        .color("000000")
                                        .build()))
                        .build());
            }
        }

        log.info("✅ [Multi-Agent] Pipeline complete. Total blocks: {}", allBlocks.size());
        return allBlocks;
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

    private record SectionContent(String title, String content) {
    }
}
