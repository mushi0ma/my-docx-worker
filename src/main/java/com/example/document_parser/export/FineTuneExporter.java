package com.example.document_parser.export;

import com.example.document_parser.dto.DocumentMetadataResponse;
import com.example.document_parser.dto.DocumentMetadataResponse.*;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.Writer;
import java.util.*;

/**
 * Exporter producing OpenAI-compatible chat JSONL for fine-tuning.
 *
 * Output format (one JSON object per line):
 * {"messages": [
 * {"role": "system", "content": "You are a document analysis expert..."},
 * {"role": "user", "content": "<document content>"},
 * {"role": "assistant", "content": "<structured analysis>"}
 * ]}
 *
 * Each document produces one training example that teaches the model to:
 * 1. Understand document structure (headings, lists, tables)
 * 2. Identify key information (title, author, topics)
 * 3. Generate structured analysis responses
 *
 * Usage: GET /api/v1/documents/{jobId}/export/finetune
 */
@Component
public class FineTuneExporter implements DocumentExporter {

    private final ObjectMapper objectMapper;

    public FineTuneExporter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String format() {
        return "finetune";
    }

    @Override
    public String contentType() {
        return "application/jsonl";
    }

    @Override
    public String fileExtension() {
        return ".finetune.jsonl";
    }

    private static final String SYSTEM_PROMPT = """
            You are an expert document analyst. Given a document's content, you provide a structured JSON analysis.
            Your analysis must include:
            - documentType: The type of document (Report, Contract, Letter, Presentation, Academic, Technical, Other)
            - language: Primary language code (en, ru, kk, mixed)
            - structure: A breakdown of the document's structure (headings, sections, tables, lists)
            - keyTopics: Array of key topics discussed
            - summary: A concise 2-3 sentence summary in the document's language
            - complexity: low, medium, or high based on vocabulary, structure, and topic depth
            - formatting: Notable formatting patterns used in the document
            Respond with valid JSON only, no markdown wrappers.""";

    @Override
    public void export(DocumentMetadataResponse doc, String jobId, Writer writer) throws IOException {
        if (doc.getContentBlocks() == null || doc.getContentBlocks().isEmpty()) {
            return;
        }

        // Build the "user" content — the document text with structural annotations
        String userContent = buildUserContent(doc);

        // Build the "assistant" response — structured analysis
        String assistantContent = buildAssistantResponse(doc);

        // Compose the training example
        Map<String, Object> trainingExample = new LinkedHashMap<>();
        List<Map<String, String>> messages = new ArrayList<>();

        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        messages.add(Map.of("role", "user", "content", "Analyze this document:\n\n" + userContent));
        messages.add(Map.of("role", "assistant", "content", assistantContent));

        trainingExample.put("messages", messages);

        writer.write(objectMapper.writeValueAsString(trainingExample));
        writer.write('\n');
    }

    private String buildUserContent(DocumentMetadataResponse doc) {
        StringBuilder sb = new StringBuilder();

        // Include metadata header
        if (doc.getStats() != null) {
            DocumentStats s = doc.getStats();
            if (s.getTitle() != null)
                sb.append("[Title: ").append(s.getTitle()).append("]\n");
            if (s.getAuthor() != null)
                sb.append("[Author: ").append(s.getAuthor()).append("]\n");
            sb.append("\n");
        }

        // Build annotated content with structural markers
        for (DocumentBlock block : doc.getContentBlocks()) {
            if (block == null || block.getType() == null)
                continue;

            switch (block.getType()) {
                case "PARAGRAPH" -> {
                    if (block.getSemanticLevel() != null) {
                        sb.append("[H").append(block.getSemanticLevel()).append("] ");
                    }
                    appendBlockText(sb, block);
                    sb.append("\n\n");
                }
                case "LIST_ITEM" -> {
                    String indent = block.getListLevel() != null
                            ? "  ".repeat(safeParseInt(block.getListLevel()))
                            : "";
                    sb.append(indent).append("• ");
                    appendBlockText(sb, block);
                    sb.append("\n");
                }
                case "TABLE" -> {
                    sb.append("[TABLE]\n");
                    if (block.getTableRows() != null) {
                        for (TableRowData row : block.getTableRows()) {
                            if (row.getCells() == null)
                                continue;
                            StringJoiner cells = new StringJoiner(" | ");
                            for (TableCellData cell : row.getCells()) {
                                cells.add(cell.getText() != null ? cell.getText() : "");
                            }
                            sb.append(cells).append("\n");
                        }
                    }
                    sb.append("[/TABLE]\n\n");
                }
                case "CODE_BLOCK" -> {
                    sb.append("[CODE]\n");
                    appendBlockText(sb, block);
                    sb.append("\n[/CODE]\n\n");
                }
                case "IMAGE" -> sb.append("[IMAGE: ").append(
                        block.getImageName() != null ? block.getImageName() : "embedded").append("]\n\n");
                default -> {
                    appendBlockText(sb, block);
                    sb.append("\n\n");
                }
            }
        }
        return sb.toString().trim();
    }

    private String buildAssistantResponse(DocumentMetadataResponse doc) {
        Map<String, Object> analysis = new LinkedHashMap<>();

        // Document type inference
        analysis.put("documentType", inferDocumentType(doc));

        // Language detection from content
        analysis.put("language", inferLanguage(doc));

        // Structure breakdown
        Map<String, Integer> structure = new LinkedHashMap<>();
        int headings = 0, paragraphs = 0, tables = 0, lists = 0, images = 0;
        for (DocumentBlock block : doc.getContentBlocks()) {
            if (block == null || block.getType() == null)
                continue;
            switch (block.getType()) {
                case "PARAGRAPH" -> {
                    if (block.getSemanticLevel() != null)
                        headings++;
                    else
                        paragraphs++;
                }
                case "TABLE" -> tables++;
                case "LIST_ITEM" -> lists++;
                case "IMAGE" -> images++;
            }
        }
        structure.put("headings", headings);
        structure.put("paragraphs", paragraphs);
        structure.put("tables", tables);
        structure.put("list_items", lists);
        structure.put("images", images);
        analysis.put("structure", structure);

        // Key topics from headings
        List<String> topics = doc.getContentBlocks().stream()
                .filter(b -> b != null && b.getSemanticLevel() != null && b.getText() != null)
                .map(DocumentBlock::getText)
                .limit(10)
                .toList();
        analysis.put("keyTopics", topics);

        // Summary from stats
        String title = doc.getStats() != null && doc.getStats().getTitle() != null
                ? doc.getStats().getTitle()
                : doc.getFileName();
        analysis.put("summary", "Document '" + title + "' contains " +
                doc.getContentBlocks().size() + " content blocks including " +
                headings + " headings, " + tables + " tables, and " + images + " images.");

        analysis.put("complexity",
                paragraphs > 50 || tables > 5 ? "high" : paragraphs > 20 || tables > 2 ? "medium" : "low");

        // Formatting summary
        List<String> formattingNotes = new ArrayList<>();
        boolean hasBold = doc.getContentBlocks().stream()
                .filter(b -> b.getRuns() != null)
                .flatMap(b -> b.getRuns().stream())
                .anyMatch(r -> Boolean.TRUE.equals(r.getIsBold()));
        if (hasBold)
            formattingNotes.add("bold emphasis");
        boolean hasLinks = doc.getContentBlocks().stream()
                .filter(b -> b.getRuns() != null)
                .flatMap(b -> b.getRuns().stream())
                .anyMatch(r -> r.getHyperlink() != null);
        if (hasLinks)
            formattingNotes.add("hyperlinks");
        if (images > 0)
            formattingNotes.add("embedded images");
        analysis.put("formatting", formattingNotes);

        try {
            return objectMapper.writeValueAsString(analysis);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String inferDocumentType(DocumentMetadataResponse doc) {
        if (doc.getStats() != null && doc.getStats().getTitle() != null) {
            String title = doc.getStats().getTitle().toLowerCase();
            if (title.contains("отчет") || title.contains("report"))
                return "Report";
            if (title.contains("договор") || title.contains("contract"))
                return "Contract";
            if (title.contains("лаб") || title.contains("lab"))
                return "Academic";
        }
        return "Other";
    }

    private String inferLanguage(DocumentMetadataResponse doc) {
        String sample = doc.getContentBlocks().stream()
                .filter(b -> b.getText() != null && b.getText().length() > 20)
                .map(DocumentBlock::getText)
                .findFirst().orElse("");
        if (sample.matches(".*[а-яА-Я].*"))
            return "ru";
        if (sample.matches(".*[a-zA-Z].*"))
            return "en";
        return "mixed";
    }

    private void appendBlockText(StringBuilder sb, DocumentBlock block) {
        if (block.getText() != null) {
            sb.append(block.getText());
        }
    }

    private int safeParseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
