package com.example.document_parser.export;

import com.example.document_parser.dto.DocumentMetadataResponse;
import com.example.document_parser.dto.DocumentMetadataResponse.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TsvDocumentExporterTest {

    private TsvDocumentExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new TsvDocumentExporter();
    }

    @Test
    void format_returnsTsv() {
        assertEquals("tsv", exporter.format());
    }

    @Test
    void contentType_isTsv() {
        assertTrue(exporter.contentType().contains("tab-separated"));
    }

    @Test
    void export_nullBlocks_producesHeaderOnly() throws IOException {
        DocumentMetadataResponse doc = DocumentMetadataResponse.builder().build();

        StringWriter writer = new StringWriter();
        exporter.export(doc, "test-id", writer);

        String output = writer.toString();
        String[] lines = output.strip().split("\n");
        assertEquals(1, lines.length, "Only header row expected");
        assertTrue(lines[0].contains("document_id"), "Header should contain document_id");
        assertTrue(lines[0].contains("semantic_level"), "Header should contain semantic_level");
    }

    @Test
    void export_singleParagraph_producesHeaderAndDataRow() throws IOException {
        DocumentBlock block = DocumentBlock.builder()
                .type("PARAGRAPH")
                .text("Hello world")
                .styleName("Normal")
                .alignment("LEFT")
                .build();

        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .contentBlocks(List.of(block))
                .build();

        StringWriter writer = new StringWriter();
        exporter.export(doc, "job-1", writer);

        String[] lines = writer.toString().strip().split("\n");
        assertEquals(2, lines.length, "Header + 1 data row");
        assertTrue(lines[1].contains("PARAGRAPH"));
        assertTrue(lines[1].contains("Hello world"));
        assertTrue(lines[1].contains("Normal"));
    }

    @Test
    void export_blockWithFormattingRuns_includesAllTags() throws IOException {
        RunData run = RunData.builder()
                .text("Text")
                .isBold(true)
                .isItalic(true)
                .isStrikeThrough(true)
                .isSubscript(true)
                .fontFamily("Times New Roman")
                .fontSize(12.0)
                .build();

        DocumentBlock block = DocumentBlock.builder()
                .type("PARAGRAPH")
                .text("Formatted text")
                .runs(List.of(run))
                .build();

        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .contentBlocks(List.of(block))
                .build();

        StringWriter writer = new StringWriter();
        exporter.export(doc, "job-2", writer);

        String output = writer.toString();
        assertTrue(output.contains("bold"));
        assertTrue(output.contains("italic"));
        assertTrue(output.contains("strikethrough"));
        assertTrue(output.contains("subscript"));
        assertTrue(output.contains("Times New Roman"));
    }

    @Test
    void export_tableBlock_expandsCells() throws IOException {
        TableCellData c1 = TableCellData.builder().text("Cell 1").build();
        TableCellData c2 = TableCellData.builder().text("Cell 2").build();
        TableRowData row = TableRowData.builder().cells(List.of(c1, c2)).build();

        DocumentBlock table = DocumentBlock.builder()
                .type("TABLE")
                .text("A table")
                .tableRows(List.of(row))
                .build();

        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .contentBlocks(List.of(table))
                .build();

        StringWriter writer = new StringWriter();
        exporter.export(doc, "job-3", writer);

        String[] lines = writer.toString().strip().split("\n");
        // Header + 1 TABLE row + 2 TABLE_CELL rows
        assertTrue(lines.length >= 4, "Should have header + table row + cell rows");

        boolean hasTableCell = false;
        for (String line : lines) {
            if (line.contains("TABLE_CELL"))
                hasTableCell = true;
        }
        assertTrue(hasTableCell, "Should contain TABLE_CELL rows");
    }

    @Test
    void export_textWithTabsAndNewlines_properlyEscaped() throws IOException {
        DocumentBlock block = DocumentBlock.builder()
                .type("PARAGRAPH")
                .text("Line 1\nLine 2\tTabbed")
                .build();

        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .contentBlocks(List.of(block))
                .build();

        StringWriter writer = new StringWriter();
        exporter.export(doc, "job-4", writer);

        String[] lines = writer.toString().strip().split("\n");
        // The actual text should not contain raw tabs or newlines between TSV fields
        String dataRow = lines[1];
        String[] fields = dataRow.split("\t");
        // Should have the correct number of columns (not broken by embedded tabs)
        assertTrue(fields.length >= 10, "Tab in text should be escaped, not break columns");
    }
}
