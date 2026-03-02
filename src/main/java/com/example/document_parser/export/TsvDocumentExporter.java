package com.example.document_parser.export;

import com.example.document_parser.dto.DocumentMetadataResponse;
import com.example.document_parser.dto.DocumentMetadataResponse.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.StringJoiner;

/**
 * Enhanced TSV exporter optimized for tabular ML training data and system
 * prompting.
 *
 * Improvements over baseline:
 * - Added columns: semantic_level, list_level, has_image, hyperlink_count,
 * strikethrough, subscript, superscript, highlight_color
 * - TABLE blocks emit one row per cell for better tabular training data
 * - Proper TSV escaping (tabs and newlines in text content)
 * - Dominant font/size extraction across all runs
 */
@Component
public class TsvDocumentExporter implements DocumentExporter {

    @Override
    public String format() {
        return "tsv";
    }

    @Override
    public String contentType() {
        return "text/tab-separated-values;charset=UTF-8";
    }

    @Override
    public String fileExtension() {
        return ".tsv";
    }

    private static final String HEADER = String.join("\t",
            "document_id", "block_index", "block_type", "style", "alignment",
            "semantic_level", "text", "formatting_tags", "font_family", "font_size",
            "list_level", "has_image", "hyperlink_count",
            "strikethrough", "subscript", "superscript", "highlight");

    @Override
    public void export(DocumentMetadataResponse doc, String jobId, Writer writer) throws IOException {
        writer.write(HEADER + "\n");

        if (doc.getContentBlocks() == null)
            return;

        int idx = 0;
        for (DocumentBlock block : doc.getContentBlocks()) {
            if ("TABLE".equals(block.getType()) && block.getTableRows() != null) {
                // Emit table cells as individual rows for better training data
                idx = exportTableCells(writer, jobId, block, idx);
            } else {
                exportBlock(writer, jobId, block, idx++);
            }
        }
        writer.flush();
    }

    private void exportBlock(Writer writer, String jobId, DocumentBlock block, int idx) throws IOException {
        String tags = buildTags(block.getRuns());
        String font = dominantFont(block.getRuns());
        String size = dominantSize(block.getRuns());
        int hyperlinkCount = countHyperlinks(block.getRuns());
        boolean hasStrike = hasFormatting(block.getRuns(), RunData::getIsStrikeThrough);
        boolean hasSub = hasFormatting(block.getRuns(), RunData::getIsSubscript);
        boolean hasSup = hasFormatting(block.getRuns(), RunData::getIsSuperscript);
        String highlight = dominantHighlight(block.getRuns());

        writeRow(writer, jobId, idx,
                safe(block.getType()), safe(block.getStyleName()), safe(block.getAlignment()),
                block.getSemanticLevel() != null ? String.valueOf(block.getSemanticLevel()) : "",
                escapeTsv(block.getText()),
                tags, font, size,
                block.getListLevel() != null ? block.getListLevel() : "",
                block.getImageName() != null ? "true" : "false",
                String.valueOf(hyperlinkCount),
                String.valueOf(hasStrike), String.valueOf(hasSub), String.valueOf(hasSup),
                highlight);
    }

    private int exportTableCells(Writer writer, String jobId, DocumentBlock block, int startIdx) throws IOException {
        int idx = startIdx;
        // Emit one header row for the table itself
        writeRow(writer, jobId, idx++,
                "TABLE", safe(block.getStyleName()), safe(block.getAlignment()),
                "", escapeTsv(block.getText()),
                "", "", "",
                "", "false", "0",
                "false", "false", "false", "");

        // Emit individual cells
        for (TableRowData row : block.getTableRows()) {
            if (row.getCells() == null)
                continue;
            for (TableCellData cell : row.getCells()) {
                String cellText = cell.getText() != null ? cell.getText() : "";
                // Process cell content blocks for formatting
                String cellTags = "";
                if (cell.getCellContent() != null) {
                    StringJoiner cellTagJoiner = new StringJoiner(",");
                    for (DocumentBlock cb : cell.getCellContent()) {
                        String t = buildTags(cb.getRuns());
                        if (!t.isEmpty())
                            cellTagJoiner.add(t);
                    }
                    cellTags = cellTagJoiner.toString();
                }

                writeRow(writer, jobId, idx++,
                        "TABLE_CELL", "", "",
                        "", escapeTsv(cellText),
                        cellTags, "", "",
                        "", "false", "0",
                        "false", "false", "false", "");
            }
        }
        return idx;
    }

    private void writeRow(Writer writer, String jobId, int idx, String... fields) throws IOException {
        writer.write(safe(jobId));
        writer.write('\t');
        writer.write(String.valueOf(idx));
        for (String field : fields) {
            writer.write('\t');
            writer.write(field != null ? field : "");
        }
        writer.write('\n');
    }

    private String buildTags(List<RunData> runs) {
        if (runs == null || runs.isEmpty())
            return "";
        StringJoiner j = new StringJoiner(",");
        boolean bold = false, italic = false, underline = false, link = false;
        boolean strike = false, sub = false, sup = false;
        for (RunData r : runs) {
            if (Boolean.TRUE.equals(r.getIsBold()))
                bold = true;
            if (Boolean.TRUE.equals(r.getIsItalic()))
                italic = true;
            if (Boolean.TRUE.equals(r.getIsUnderline()))
                underline = true;
            if (r.getHyperlink() != null)
                link = true;
            if (Boolean.TRUE.equals(r.getIsStrikeThrough()))
                strike = true;
            if (Boolean.TRUE.equals(r.getIsSubscript()))
                sub = true;
            if (Boolean.TRUE.equals(r.getIsSuperscript()))
                sup = true;
        }
        if (bold)
            j.add("bold");
        if (italic)
            j.add("italic");
        if (underline)
            j.add("underline");
        if (strike)
            j.add("strikethrough");
        if (sub)
            j.add("subscript");
        if (sup)
            j.add("superscript");
        if (link)
            j.add("hyperlink");
        return j.toString();
    }

    private String dominantFont(List<RunData> runs) {
        if (runs == null)
            return "";
        return runs.stream()
                .filter(r -> r.getFontFamily() != null)
                .map(RunData::getFontFamily)
                .findFirst().orElse("");
    }

    private String dominantSize(List<RunData> runs) {
        if (runs == null)
            return "";
        return runs.stream()
                .filter(r -> r.getFontSize() != null)
                .map(r -> String.valueOf(r.getFontSize()))
                .findFirst().orElse("");
    }

    private String dominantHighlight(List<RunData> runs) {
        if (runs == null)
            return "";
        return runs.stream()
                .filter(r -> r.getTextHighlightColor() != null)
                .map(RunData::getTextHighlightColor)
                .findFirst().orElse("");
    }

    private int countHyperlinks(List<RunData> runs) {
        if (runs == null)
            return 0;
        return (int) runs.stream().filter(r -> r.getHyperlink() != null).count();
    }

    @FunctionalInterface
    private interface BooleanExtractor {
        Boolean extract(RunData run);
    }

    private boolean hasFormatting(List<RunData> runs, BooleanExtractor extractor) {
        if (runs == null)
            return false;
        return runs.stream().anyMatch(r -> Boolean.TRUE.equals(extractor.extract(r)));
    }

    private String escapeTsv(String text) {
        if (text == null)
            return "";
        return text.replace("\t", " ").replace("\n", "\\n").replace("\r", "");
    }

    private String safe(String value) {
        return value != null ? value : "";
    }
}