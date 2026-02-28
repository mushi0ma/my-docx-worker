package com.example.document_parser.service;

import com.example.document_parser.dto.DocumentMetadataResponse;
import com.example.document_parser.dto.DocumentMetadataResponse.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Конвертирует DocumentMetadataResponse в TSV-формат для файн-тюнинга LLM.
 *
 * Формат строки (для инструкционного файн-тюнинга):
 *   document_id \t block_index \t block_type \t style \t alignment \t text \t formatting_tags
 *
 * Этот формат совместим с:
 * - OpenAI fine-tuning (после конвертации в JSONL)
 * - HuggingFace datasets (CSV/TSV loader)
 * - LlamaIndex document ingestion
 */
@Service
public class TsvExportService {

    // Заголовок TSV — описывает каждую колонку
    public static final String TSV_HEADER =
            "document_id\tblock_index\tblock_type\tstyle\talignment\ttext\tformatting_tags\tfont_family\tfont_size";

    /**
     * Конвертирует весь документ в TSV-строку.
     * Каждый блок = одна строка. Раны схлопываются в теги форматирования.
     */
    public String toTsv(DocumentMetadataResponse doc, String jobId) {
        StringBuilder sb = new StringBuilder();
        sb.append(TSV_HEADER).append("\n");

        List<DocumentBlock> blocks = doc.getContentBlocks();
        if (blocks == null || blocks.isEmpty()) return sb.toString();

        for (int i = 0; i < blocks.size(); i++) {
            DocumentBlock block = blocks.get(i);
            String line = buildTsvLine(jobId, i, block);
            if (line != null) sb.append(line).append("\n");

            // TABLE: разворачиваем ячейки как отдельные строки
            if ("TABLE".equals(block.getType()) && block.getTableRows() != null) {
                int subIdx = 0;
                for (TableRowData row : block.getTableRows()) {
                    if (row.getCells() == null) continue;
                    for (TableCellData cell : row.getCells()) {
                        if (cell.getText() == null || cell.getText().isBlank()) continue;
                        sb.append(buildCellTsvLine(jobId, i, subIdx++, cell)).append("\n");
                    }
                }
            }
        }

        return sb.toString();
    }

    /**
     * Конвертирует документ в JSONL-формат для OpenAI fine-tuning.
     * Каждая строка — JSON-объект {"prompt": "...", "completion": "..."}.
     */
    public String toJsonl(DocumentMetadataResponse doc, String jobId) {
        StringBuilder sb = new StringBuilder();
        List<DocumentBlock> blocks = doc.getContentBlocks();
        if (blocks == null) return sb.toString();

        for (DocumentBlock block : blocks) {
            String text = block.getText();
            if (text == null || text.isBlank()) continue;
            if ("IMAGE".equals(block.getType())) continue;

            String type = block.getType();
            String style = block.getStyleName() != null ? block.getStyleName() : "";
            String tags = buildFormattingTags(block.getRuns());

            // Промпт: контекст → что это за блок
            String prompt = "Document: " + jobId + ". Block type: " + type +
                    (style.isBlank() ? "" : ", style: " + style) + ". Text: " + sanitize(text);
            String completion = type.toLowerCase().replace("_", " ") +
                    (tags.isBlank() ? "" : " [" + tags + "]");

            sb.append("{\"prompt\":\"").append(escapeJson(prompt))
                    .append("\",\"completion\":\"").append(escapeJson(completion))
                    .append("\"}\n");
        }
        return sb.toString();
    }

    private String buildTsvLine(String jobId, int index, DocumentBlock block) {
        if (block == null || block.getType() == null) return null;
        String text = block.getText();
        // Пропускаем пустые блоки и IMAGE (там нет текста для обучения)
        if ((text == null || text.isBlank()) && !"TABLE".equals(block.getType())) return null;

        String formattingTags = buildFormattingTags(block.getRuns());
        String fontFamily = extractDominantFont(block.getRuns());
        String fontSize = extractDominantFontSize(block.getRuns());

        return joinTsv(
                sanitize(jobId),
                String.valueOf(index),
                sanitize(block.getType()),
                sanitize(block.getStyleName()),
                sanitize(block.getAlignment()),
                sanitize(text),
                sanitize(formattingTags),
                sanitize(fontFamily),
                sanitize(fontSize)
        );
    }

    private String buildCellTsvLine(String jobId, int tableIdx, int cellIdx, TableCellData cell) {
        return joinTsv(
                sanitize(jobId),
                tableIdx + "_cell_" + cellIdx,
                "TABLE_CELL",
                "",
                "",
                sanitize(cell.getText()),
                "",
                "",
                ""
        );
    }

    /**
     * Собирает теги форматирования из списка ранов.
     * Пример: "bold,italic,font:TimesNewRoman"
     */
    private String buildFormattingTags(List<RunData> runs) {
        if (runs == null || runs.isEmpty()) return "";

        boolean hasBold = false, hasItalic = false, hasUnderline = false, hasHyperlink = false;
        for (RunData r : runs) {
            if (Boolean.TRUE.equals(r.getIsBold())) hasBold = true;
            if (Boolean.TRUE.equals(r.getIsItalic())) hasItalic = true;
            if (Boolean.TRUE.equals(r.getIsUnderline())) hasUnderline = true;
            if (r.getHyperlink() != null) hasHyperlink = true;
        }

        StringJoiner tags = new StringJoiner(",");
        if (hasBold) tags.add("bold");
        if (hasItalic) tags.add("italic");
        if (hasUnderline) tags.add("underline");
        if (hasHyperlink) tags.add("hyperlink");
        return tags.toString();
    }

    private String extractDominantFont(List<RunData> runs) {
        if (runs == null || runs.isEmpty()) return "";
        // Берём шрифт первого непустого рана как доминирующий
        return runs.stream()
                .filter(r -> r.getFontFamily() != null)
                .map(RunData::getFontFamily)
                .findFirst()
                .orElse("");
    }

    private String extractDominantFontSize(List<RunData> runs) {
        if (runs == null || runs.isEmpty()) return "";
        return runs.stream()
                .filter(r -> r.getFontSize() != null)
                .map(r -> String.valueOf(r.getFontSize()))
                .findFirst()
                .orElse("");
    }

    /** Склеивает поля в TSV-строку. */
    private String joinTsv(String... fields) {
        return String.join("\t", fields);
    }

    /**
     * Чистит текст для TSV: убирает переносы строк и табы, которые сломают формат.
     */
    private String sanitize(String text) {
        if (text == null) return "";
        return text
                .strip()
                .replace("\t", " ")      // табы сломают TSV-разметку
                .replace("\n", " ")      // переносы — в пробел
                .replace("\r", "")
                .replace("\"", "'");     // кавычки → апострофы для безопасности
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\t", "\\t");
    }
}