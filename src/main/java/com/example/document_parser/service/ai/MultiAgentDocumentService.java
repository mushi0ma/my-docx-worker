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
 * Разбивает генерацию документа на 3 этапа с выделенными моделями:
 * 1. Planning Agent (Groq) — создаёт структуру (список заголовков секций)
 * 2. Writing Agent (Gemini) — пишет контент для каждой секции
 * 3. Formatting Agent (Groq) — превращает текст в DocumentBlock JSON
 *
 * Каждый агент использует оптимальную для задачи LLM.
 */
@Service
public class MultiAgentDocumentService {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentDocumentService.class);
    private static final int FORMAT_RETRIES = 2;

    private final ChatLanguageModel plannerModel;
    private final ChatLanguageModel writerModel;
    private final ChatLanguageModel formatterModel;
    private final ObjectMapper objectMapper;

    public MultiAgentDocumentService(
            @Qualifier("plannerModel") ChatLanguageModel plannerModel,
            @Qualifier("writerModel") ChatLanguageModel writerModel,
            @Qualifier("formatterModel") ChatLanguageModel formatterModel,
            ObjectMapper objectMapper) {
        this.plannerModel = plannerModel;
        this.writerModel = writerModel;
        this.formatterModel = formatterModel;
        this.objectMapper = objectMapper;
    }

    /**
     * Выполняет полный 3-этапный пайплайн генерации документа.
     *
     * @param userPrompt запрос пользователя
     * @param ragContext RAG-контекст из векторной базы (может быть пустым)
     * @return готовый список DocumentBlock для передачи в DynamicDocxBuilderService
     */
    public List<DocumentBlock> generateWithAgentChain(String userPrompt, String ragContext) throws Exception {

        // =====================================================
        // STEP 1: Planning Agent (Groq) — структура документа
        // =====================================================
        log.info("📋 [Planning Agent / Groq] Generating document structure...");
        String planPrompt = AiPrompts.planDocument(userPrompt);
        String planResponse = plannerModel.generate(planPrompt);
        planResponse = stripMarkdownFences(planResponse);

        List<String> sections;
        try {
            sections = objectMapper.readValue(planResponse, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("Planning Agent returned invalid JSON, using fallback structure: {}", e.getMessage());
            sections = List.of(userPrompt, "Введение", "Основная часть", "Заключение");
        }

        // Validate: at least 2 sections, no more than 15
        if (sections.size() < 2) {
            sections = List.of(userPrompt, "Введение", "Основная часть", "Заключение");
            log.warn("Planning Agent returned too few sections, using fallback");
        } else if (sections.size() > 15) {
            sections = sections.subList(0, 15);
            log.warn("Planning Agent returned too many sections, truncated to 15");
        }
        log.info("📋 [Planning Agent] Generated {} sections: {}", sections.size(), sections);

        // =====================================================
        // STEP 2: Writing Agent (Gemini) — контент каждой секции
        // =====================================================
        log.info("✍️ [Writing Agent / Gemini] Writing content for {} sections...", sections.size());
        List<SectionContent> writtenSections = new ArrayList<>();
        StringBuilder previousContext = new StringBuilder();

        String safeRagContext = (ragContext != null) ? ragContext : "";

        for (int i = 0; i < sections.size(); i++) {
            String sectionTitle = sections.get(i);
            log.info("✍️ [Writing Agent] Section {}/{}: {}", i + 1, sections.size(), sectionTitle);

            String writePrompt = AiPrompts.writeSection(sectionTitle, userPrompt, previousContext.toString(),
                    safeRagContext);
            String content = writerModel.generate(writePrompt);
            writtenSections.add(new SectionContent(sectionTitle, content));

            // Accumulate context for next sections (limit to ~3000 chars)
            previousContext.append(sectionTitle).append(": ").append(
                    content.length() > 500 ? content.substring(0, 500) + "..." : content).append("\n");

            if (previousContext.length() > 3000) {
                previousContext = new StringBuilder(previousContext.substring(previousContext.length() - 2000));
            }
        }

        // =====================================================
        // STEP 3: Formatting Agent (Groq) — текст → DocumentBlock JSON
        // =====================================================
        log.info("🎨 [Formatting Agent / Groq] Converting {} sections to DocumentBlocks...", writtenSections.size());
        List<DocumentBlock> allBlocks = new ArrayList<>();

        for (int i = 0; i < writtenSections.size(); i++) {
            SectionContent sc = writtenSections.get(i);
            log.info("🎨 [Formatting Agent] Formatting section {}/{}: {}", i + 1, writtenSections.size(), sc.title());

            List<DocumentBlock> sectionBlocks = formatSectionWithRetry(sc, i == 0);
            allBlocks.addAll(sectionBlocks);
        }

        log.info("✅ [Multi-Agent] Pipeline complete. Total blocks: {}", allBlocks.size());
        return allBlocks;
    }

    /**
     * Форматирует секцию с ретраями при ошибках JSON-парсинга.
     */
    private List<DocumentBlock> formatSectionWithRetry(SectionContent sc, boolean isFirstSection) {
        for (int attempt = 1; attempt <= FORMAT_RETRIES; attempt++) {
            try {
                String formatPrompt = AiPrompts.formatToBlocks(sc.title(), sc.content(), isFirstSection);
                String blocksJson = formatterModel.generate(formatPrompt);
                blocksJson = stripMarkdownFences(blocksJson);

                List<DocumentBlock> blocks = objectMapper.readValue(
                        blocksJson, new TypeReference<>() {
                        });
                if (!blocks.isEmpty()) {
                    return blocks;
                }
            } catch (Exception e) {
                log.warn("Formatting attempt {}/{} failed for '{}': {}",
                        attempt, FORMAT_RETRIES, sc.title(), e.getMessage());
            }
        }

        // Final fallback: create plain paragraph blocks
        log.warn("All formatting attempts failed for '{}', adding as plain text.", sc.title());
        List<DocumentBlock> fallback = new ArrayList<>();
        fallback.add(DocumentBlock.builder()
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
        fallback.add(DocumentBlock.builder()
                .type("PARAGRAPH")
                .alignment("BOTH")
                .runs(List.of(
                        com.example.document_parser.dto.DocumentMetadataResponse.RunData.builder()
                                .text(sc.content())
                                .fontSize(12.0)
                                .color("000000")
                                .build()))
                .build());
        return fallback;
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
