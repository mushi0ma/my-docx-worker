package com.example.document_parser.export;

import com.example.document_parser.dto.DocumentMetadataResponse;
import com.example.document_parser.dto.DocumentMetadataResponse.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonlDocumentExporterTest {

    private JsonlDocumentExporter exporter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().build();
        exporter = new JsonlDocumentExporter(objectMapper);
    }

    @Test
    void format_returnsJsonl() {
        assertEquals("jsonl", exporter.format());
    }

    @Test
    void export_nullBlocks_producesNoOutput() throws IOException {
        DocumentMetadataResponse doc = DocumentMetadataResponse.builder().build();

        StringWriter writer = new StringWriter();
        exporter.export(doc, "test-id", writer);

        assertEquals("", writer.toString());
    }

    @Test
    void export_emptyBlocks_producesNoOutput() throws IOException {
        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .contentBlocks(List.of())
                .build();

        StringWriter writer = new StringWriter();
        exporter.export(doc, "test-id", writer);

        assertEquals("", writer.toString());
    }

    @Test
    void export_singleBlock_producesOneLine() throws IOException {
        DocumentBlock block = DocumentBlock.builder()
                .type("PARAGRAPH")
                .text("Hello world")
                .styleName("Normal")
                .build();

        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .fileName("test.docx")
                .contentBlocks(List.of(block))
                .build();

        StringWriter writer = new StringWriter();
        exporter.export(doc, "job-123", writer);

        String output = writer.toString();
        String[] lines = output.strip().split("\n");
        assertEquals(1, lines.length, "Should produce exactly 1 line");

        // Verify it's valid JSON
        var parsed = objectMapper.readTree(lines[0]);
        assertEquals("job-123", parsed.get("document_id").asText());
        assertEquals("PARAGRAPH", parsed.get("block_type").asText());
        assertEquals("Hello world", parsed.get("text").asText());
        assertEquals(0, parsed.get("block_index").asInt());
        assertEquals(1, parsed.get("total_blocks").asInt());
    }

    @Test
    void export_multipleBlocks_producesMultipleLines() throws IOException {
        DocumentBlock b1 = DocumentBlock.builder().type("PARAGRAPH").text("First").build();
        DocumentBlock b2 = DocumentBlock.builder().type("PARAGRAPH").text("Second").build();
        DocumentBlock b3 = DocumentBlock.builder().type("IMAGE").imageName("photo.png").build();

        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .contentBlocks(List.of(b1, b2, b3))
                .build();

        StringWriter writer = new StringWriter();
        exporter.export(doc, "job-456", writer);

        String[] lines = writer.toString().strip().split("\n");
        assertEquals(3, lines.length);
    }

    @Test
    void export_blockWithRuns_includesFormattingTags() throws IOException {
        RunData boldRun = RunData.builder().text("Bold").isBold(true).fontFamily("Arial").build();
        RunData linkRun = RunData.builder().text("Link").hyperlink("http://example.com").build();

        DocumentBlock block = DocumentBlock.builder()
                .type("PARAGRAPH")
                .text("Bold Link")
                .runs(List.of(boldRun, linkRun))
                .build();

        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .contentBlocks(List.of(block))
                .build();

        StringWriter writer = new StringWriter();
        exporter.export(doc, "job-789", writer);

        String output = writer.toString();
        assertTrue(output.contains("bold"), "Should have bold formatting tag");
        assertTrue(output.contains("hyperlink"), "Should have hyperlink in formatting");
        assertTrue(output.contains("Arial"), "Should have font info");
    }

    @Test
    void export_tableBlock_includesTableData() throws IOException {
        TableCellData c1 = TableCellData.builder().text("A1").build();
        TableCellData c2 = TableCellData.builder().text("B1").build();
        TableRowData row = TableRowData.builder().cells(List.of(c1, c2)).build();

        DocumentBlock table = DocumentBlock.builder()
                .type("TABLE")
                .text("My table")
                .tableRows(List.of(row))
                .rowsCount(1)
                .columnsCount(2)
                .build();

        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .contentBlocks(List.of(table))
                .build();

        StringWriter writer = new StringWriter();
        exporter.export(doc, "job-table", writer);

        String output = writer.toString();
        assertTrue(output.contains("table_data"), "Should include table_data field");
        assertTrue(output.contains("A1"));
        assertTrue(output.contains("B1"));
    }
}
