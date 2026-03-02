package com.example.document_parser.service;

import com.example.document_parser.dto.DocumentMetadataResponse;
import com.example.document_parser.service.ai.AiPrompts;
import tools.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Сервис самокоррекции документов через AI.
 *
 * Трёхуровневая стратегия (экономия токенов):
 * 1. Java-валидация (~0ms, 0 токенов) — отсекает 80%+ документов
 * 2. LLM полного документа (если документ ≤ MAX_JSON_CHARS)
 * 3. LLM поблочно — но не более MAX_BLOCK_CORRECTIONS блоков
 *
 * ИСПРАВЛЕНО: добавлен лимит на количество LLM-вызовов в поблочном режиме.
 * Без лимита документ из 1000 пустых блоков делал 1000 API-запросов.
 */
@Service
public class SelfCorrectionService {

    private static final Logger log = LoggerFactory.getLogger(SelfCorrectionService.class);

    @Value("${app.ai.correction.max-json-chars:15000}")
    private int maxJsonCharsForFullCorrection;

    @Value("${app.ai.correction.max-empty-ratio:0.6}")
    private double maxEmptyBlockRatio;

    /**
     * Максимум LLM-вызовов при поблочной коррекции.
     * Защищает от API-перерасхода на документах с тысячами пустых блоков.
     */
    @Value("${app.ai.correction.max-block-corrections:50}")
    private int maxBlockCorrections;

    private final ChatLanguageModel correctorModel;
    private final ObjectMapper objectMapper;

    public SelfCorrectionService(
            @Qualifier("correctorModel") ChatLanguageModel correctorModel,
            ObjectMapper objectMapper) {
        this.correctorModel = correctorModel;
        this.objectMapper = objectMapper;
    }

    public DocumentMetadataResponse validateAndCorrect(DocumentMetadataResponse doc) {
        log.info("Self-correction started. file={}", doc.getFileName());

        List<String> issues = structuralValidate(doc);
        if (issues.isEmpty()) {
            log.info("Document passed structural validation. file={}", doc.getFileName());
            return doc;
        }

        log.warn("Structural issues found: {}. file={}", issues, doc.getFileName());
        return correctWithLlm(doc);
    }

    // ---- Структурная валидация ----

    private List<String> structuralValidate(DocumentMetadataResponse doc) {
        List<String> issues = new ArrayList<>();

        if (doc.getContentBlocks() == null || doc.getContentBlocks().isEmpty()) {
            issues.add("no_content_blocks");
            return issues;
        }

        long total = doc.getContentBlocks().size();
        long emptyTextBlocks = doc.getContentBlocks().stream()
                .filter(b -> !isVisualBlock(b))
                .filter(b -> b.getText() == null || b.getText().isBlank())
                .count();

        double emptyRatio = (double) emptyTextBlocks / total;
        if (emptyRatio > maxEmptyBlockRatio) {
            issues.add("high_empty_ratio:%.0f%%".formatted(emptyRatio * 100));
        }

        doc.getContentBlocks().stream()
                .filter(b -> "TABLE".equals(b.getType()) && b.getTableRows() != null)
                .forEach(table -> {
                    long distinctColCounts = table.getTableRows().stream()
                            .filter(r -> r.getCells() != null)
                            .mapToInt(r -> r.getCells().size())
                            .distinct()
                            .count();
                    if (distinctColCounts > 2) {
                        issues.add("broken_table:" + table.getText());
                    }
                });

        return issues;
    }

    private boolean isVisualBlock(DocumentMetadataResponse.DocumentBlock block) {
        String type = block.getType();
        return "IMAGE".equals(type) || "TABLE".equals(type) || "EMBEDDED_OBJECT".equals(type);
    }

    // ---- LLM-коррекция ----

    private DocumentMetadataResponse correctWithLlm(DocumentMetadataResponse doc) {
        try {
            String fullJson = objectMapper.writeValueAsString(doc);

            if (fullJson.length() <= maxJsonCharsForFullCorrection) {
                return correctFullDocument(fullJson);
            } else {
                log.info("Document too large ({} chars), using block-by-block. file={}",
                        fullJson.length(), doc.getFileName());
                return correctBlockByBlock(doc);
            }
        } catch (tools.jackson.core.JacksonException e) {
            log.error("JSON serialization failed during correction. file={}, error={}",
                    doc.getFileName(), e.getMessage());
            return doc;
        } catch (Exception e) {
            log.error("LLM correction failed, returning original. file={}, error={}",
                    doc.getFileName(), e.getMessage());
            return doc;
        }
    }

    private DocumentMetadataResponse correctFullDocument(String json) throws Exception {
        log.debug("Correcting full document. chars={}", json.length());
        String corrected = stripMarkdownFences(correctorModel.generate(AiPrompts.correctFullDocument(json)));
        return objectMapper.readValue(corrected, DocumentMetadataResponse.class);
    }

    /**
     * Поблочная коррекция с лимитом на количество LLM-вызовов.
     *
     * Если больных блоков больше maxBlockCorrections — оставшиеся добавляются
     * как есть (лучше неполная коррекция, чем 1000 API-запросов).
     */
    private DocumentMetadataResponse correctBlockByBlock(DocumentMetadataResponse doc) throws Exception {
        List<DocumentMetadataResponse.DocumentBlock> fixedBlocks = new ArrayList<>();
        int corrected = 0;
        int skippedDueToLimit = 0;

        for (DocumentMetadataResponse.DocumentBlock block : doc.getContentBlocks()) {
            if (isBlockHealthy(block)) {
                fixedBlocks.add(block);
                continue;
            }

            if (corrected >= maxBlockCorrections) {
                // Лимит исчерпан — добавляем как есть, не делаем LLM-запрос
                fixedBlocks.add(block);
                skippedDueToLimit++;
                continue;
            }

            fixedBlocks.add(tryCorrectBlock(block));
            corrected++;
        }

        if (skippedDueToLimit > 0) {
            log.warn("Block correction limit reached. corrected={}, skipped={}, file={}",
                    corrected, skippedDueToLimit, doc.getFileName());
        } else {
            log.info("Block-by-block correction done. corrected={}/{}, file={}",
                    corrected, doc.getContentBlocks().size(), doc.getFileName());
        }

        return DocumentMetadataResponse.builder()
                .fileName(doc.getFileName())
                .stats(doc.getStats())
                .documentStyles(doc.getDocumentStyles())
                .documentNumbering(doc.getDocumentNumbering())
                .contentBlocks(fixedBlocks)
                .build();
    }

    private DocumentMetadataResponse.DocumentBlock tryCorrectBlock(
            DocumentMetadataResponse.DocumentBlock block) {
        try {
            String blockJson = objectMapper.writeValueAsString(block);
            String fixed = stripMarkdownFences(correctorModel.generate(AiPrompts.correctSingleBlock(blockJson)));
            return objectMapper.readValue(fixed, DocumentMetadataResponse.DocumentBlock.class);
        } catch (tools.jackson.core.JacksonException e) {
            log.warn("Failed to parse corrected JSON for block type={}, keeping original. error={}",
                    block.getType(), e.getMessage());
            return block;
        } catch (Exception e) {
            log.warn("LLM error for block type={}, keeping original. error={}",
                    block.getType(), e.getMessage());
            return block;
        }
    }

    private boolean isBlockHealthy(DocumentMetadataResponse.DocumentBlock block) {
        if (isVisualBlock(block)) return true;
        return block.getText() != null && !block.getText().isBlank();
    }

    private String stripMarkdownFences(String json) {
        if (json == null) return "{}";
        String t = json.trim();
        if (t.startsWith("```")) {
            t = t.substring(t.indexOf('\n') + 1);
        }
        if (t.endsWith("```")) {
            t = t.substring(0, t.lastIndexOf("```")).trim();
        }
        return t;
    }
}