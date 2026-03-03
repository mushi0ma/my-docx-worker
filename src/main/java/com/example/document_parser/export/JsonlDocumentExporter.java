package com.example.document_parser.export;

import com.example.document_parser.dto.DocumentMetadataResponse;
import com.example.document_parser.dto.DocumentMetadataResponse.*;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.Writer;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Enhanced JSONL exporter optimized for LLM system prompting and fine-tuning.
 *
 * Each line is a self-contained JSON object with rich metadata:
 * - document_id, block_index, total_blocks — positioning context
 * - block_type, style, semantic_level — structural classification
 * - text — the content
 * - formatting — structured formatting tags (bold, italic, hyperlink, etc.)
 * - context — surrounding context hints for the LLM
 *
 * This format is directly consumable by LLM training pipelines and enables
 * better document understanding vs. the previous flat block export.
 */
@Component
public class JsonlDocumentExporter implements DocumentExporter {

    private final ObjectMapper objectMapper;

    public JsonlDocumentExporter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String format() {
        return "jsonl";
    }

    @Override
    public String contentType() {
        return "application/jsonl";
    }

    @Override
    public String fileExtension() {
        return ".jsonl";
    }

    @Override
    public void export(DocumentMetadataResponse doc, String jobId, Writer writer) throws IOException {
        if (doc.getContentBlocks() == null || doc.getContentBlocks().isEmpty()) {
            return;
        }

        List<DocumentBlock> blocks = doc.getContentBlocks();
        int total = blocks.size();

        for (int i = 0; i < total; i++) {
            DocumentBlock block = blocks.get(i);
            Map<String, Object> enrichedLine = buildEnrichedLine(
                    block, jobId, i, total, doc.getFileName());
            writer.write(objectMapper.writeValueAsString(enrichedLine));
            writer.write('\n');
        }
    }

    private Map<String, Object> buildEnrichedLine(DocumentBlock block, String jobId,
            int index, int total, String fileName) {
        Map<String, Object> line = new LinkedHashMap<>();

        // Positioning context
        line.put("document_id", jobId);
        line.put("file_name", fileName != null ? fileName : "unknown");
        line.put("block_index", index);
        line.put("total_blocks", total);

        // Structural classification
        line.put("block_type", block.getType() != null ? block.getType() : "UNKNOWN");
        line.put("style", block.getStyleName());
        line.put("semantic_level", block.getSemanticLevel());
        line.put("alignment", block.getAlignment());

        // Content
        line.put("text", block.getText() != null ? block.getText() : "");

        // Formatting tags — structured for LLM understanding
        line.put("formatting", extractFormattingTags(block));

        // List context
        if (block.getListLevel() != null) {
            line.put("list_level", block.getListLevel());
            line.put("list_num_id", block.getListNumId());
        }

        // Table structure
        if ("TABLE".equals(block.getType()) && block.getTableRows() != null) {
            line.put("table_rows", block.getRowsCount());
            line.put("table_cols", block.getColumnsCount());
            line.put("table_data", extractTableData(block));
        }

        // Image reference
        if (block.getImageName() != null) {
            line.put("image_name", block.getImageName());
            line.put("image_content_type", block.getImageContentType());
        }

        // Embedded object
        if (block.getEmbeddedObjectName() != null) {
            line.put("embedded_object", block.getEmbeddedObjectName());
        }

        // Remove null values for cleaner output
        line.values().removeIf(Objects::isNull);

        return line;
    }

    private Map<String, Object> extractFormattingTags(DocumentBlock block) {
        Map<String, Object> formatting = new LinkedHashMap<>();
        if (block.getRuns() == null || block.getRuns().isEmpty()) {
            return formatting;
        }

        boolean hasBold = false, hasItalic = false, hasUnderline = false;
        boolean hasStrikethrough = false, hasSubscript = false, hasSuperscript = false;
        boolean hasHighlight = false;
        Set<String> fonts = new LinkedHashSet<>();
        Set<String> colors = new LinkedHashSet<>();
        List<String> hyperlinks = new ArrayList<>();

        for (RunData run : block.getRuns()) {
            if (Boolean.TRUE.equals(run.getIsBold()))
                hasBold = true;
            if (Boolean.TRUE.equals(run.getIsItalic()))
                hasItalic = true;
            if (Boolean.TRUE.equals(run.getIsUnderline()))
                hasUnderline = true;
            if (Boolean.TRUE.equals(run.getIsStrikeThrough()))
                hasStrikethrough = true;
            if (Boolean.TRUE.equals(run.getIsSubscript()))
                hasSubscript = true;
            if (Boolean.TRUE.equals(run.getIsSuperscript()))
                hasSuperscript = true;
            if (run.getTextHighlightColor() != null)
                hasHighlight = true;
            if (run.getFontFamily() != null)
                fonts.add(run.getFontFamily());
            if (run.getColor() != null)
                colors.add(run.getColor());
            if (run.getHyperlink() != null) {
                hyperlinks.add(run.getHyperlink());
            }
        }

        List<String> tags = new ArrayList<>();
        if (hasBold)
            tags.add("bold");
        if (hasItalic)
            tags.add("italic");
        if (hasUnderline)
            tags.add("underline");
        if (hasStrikethrough)
            tags.add("strikethrough");
        if (hasSubscript)
            tags.add("subscript");
        if (hasSuperscript)
            tags.add("superscript");
        if (hasHighlight)
            tags.add("highlight");

        if (!tags.isEmpty())
            formatting.put("tags", tags);
        if (!fonts.isEmpty())
            formatting.put("fonts", fonts);
        if (!colors.isEmpty())
            formatting.put("colors", colors);
        if (!hyperlinks.isEmpty())
            formatting.put("hyperlinks", hyperlinks);

        // Add detailed runs for fine-grained training data
        List<Map<String, Object>> runDetails = new ArrayList<>();
        for (RunData run : block.getRuns()) {
            if (run.getText() == null || run.getText().isBlank())
                continue;
            Map<String, Object> rd = new LinkedHashMap<>();
            rd.put("text", run.getText());
            if (Boolean.TRUE.equals(run.getIsBold()))
                rd.put("bold", true);
            if (Boolean.TRUE.equals(run.getIsItalic()))
                rd.put("italic", true);
            if (run.getHyperlink() != null)
                rd.put("hyperlink", run.getHyperlink());
            if (run.getFontFamily() != null)
                rd.put("font", run.getFontFamily());
            if (run.getFontSize() != null)
                rd.put("size", run.getFontSize());
            runDetails.add(rd);
        }
        if (!runDetails.isEmpty())
            formatting.put("runs", runDetails);

        return formatting;
    }

    private List<List<String>> extractTableData(DocumentBlock block) {
        if (block.getTableRows() == null)
            return Collections.emptyList();
        return block.getTableRows().stream()
                .filter(row -> row.getCells() != null)
                .map(row -> row.getCells().stream()
                        .map(cell -> cell.getText() != null ? cell.getText() : "")
                        .collect(Collectors.toList()))
                .collect(Collectors.toList());
    }
}