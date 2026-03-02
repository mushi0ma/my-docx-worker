package com.example.document_parser.export;

import com.example.document_parser.dto.DocumentMetadataResponse;
import com.example.document_parser.dto.DocumentMetadataResponse.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FineTuneExporterTest {

    private FineTuneExporter exporter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().build();
        exporter = new FineTuneExporter(objectMapper);
    }

    @Test
    void format_returnsFinetune() {
        assertEquals("finetune", exporter.format());
    }

    @Test
    void export_nullBlocks_producesNoOutput() throws IOException {
        DocumentMetadataResponse doc = DocumentMetadataResponse.builder().build();

        StringWriter writer = new StringWriter();
        exporter.export(doc, "test-id", writer);

        assertEquals("", writer.toString());
    }

    @Test
    void export_withContent_producesValidChatJsonl() throws IOException {
        DocumentBlock block = DocumentBlock.builder()
                .type("PARAGRAPH")
                .text("Test content for analysis")
                .build();

        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .fileName("test.docx")
                .stats(DocumentStats.builder().title("Test Document").author("Author").build())
                .contentBlocks(List.of(block))
                .build();

        StringWriter writer = new StringWriter();
        exporter.export(doc, "job-ft-1", writer);

        String output = writer.toString().strip();
        assertFalse(output.isEmpty());

        // Parse the JSONL line
        var parsed = objectMapper.readTree(output);
        assertTrue(parsed.has("messages"), "Should have messages array");

        var messages = parsed.get("messages");
        assertEquals(3, messages.size(), "Should have system, user, assistant messages");
        assertEquals("system", messages.get(0).get("role").asText());
        assertEquals("user", messages.get(1).get("role").asText());
        assertEquals("assistant", messages.get(2).get("role").asText());
    }

    @Test
    void export_systemPrompt_containsInstructions() throws IOException {
        DocumentBlock block = DocumentBlock.builder()
                .type("PARAGRAPH")
                .text("Content")
                .build();

        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .contentBlocks(List.of(block))
                .build();

        StringWriter writer = new StringWriter();
        exporter.export(doc, "job-ft-2", writer);

        var parsed = objectMapper.readTree(writer.toString().strip());
        String systemContent = parsed.get("messages").get(0).get("content").asText();

        assertTrue(systemContent.contains("document analyst"), "System prompt should define role");
        assertTrue(systemContent.contains("documentType"), "System prompt should mention expected fields");
    }

    @Test
    void export_userMessage_containsDocumentContent() throws IOException {
        DocumentBlock heading = DocumentBlock.builder()
                .type("PARAGRAPH")
                .text("Introduction")
                .semanticLevel(1)
                .build();
        DocumentBlock para = DocumentBlock.builder()
                .type("PARAGRAPH")
                .text("This is the body text")
                .build();

        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .stats(DocumentStats.builder().title("My Report").build())
                .contentBlocks(List.of(heading, para))
                .build();

        StringWriter writer = new StringWriter();
        exporter.export(doc, "job-ft-3", writer);

        var parsed = objectMapper.readTree(writer.toString().strip());
        String userContent = parsed.get("messages").get(1).get("content").asText();

        assertTrue(userContent.contains("Introduction"), "Should contain heading text");
        assertTrue(userContent.contains("body text"), "Should contain paragraph text");
        assertTrue(userContent.contains("[H1]"), "Should have structural annotation for heading");
    }

    @Test
    void export_assistantResponse_isValidJson() throws IOException {
        DocumentBlock block = DocumentBlock.builder()
                .type("PARAGRAPH")
                .text("Content paragraph here")
                .build();

        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .fileName("analysis.docx")
                .contentBlocks(List.of(block))
                .build();

        StringWriter writer = new StringWriter();
        exporter.export(doc, "job-ft-4", writer);

        var parsed = objectMapper.readTree(writer.toString().strip());
        String assistantContent = parsed.get("messages").get(2).get("content").asText();

        // The assistant response should be valid JSON
        var assistantJson = objectMapper.readTree(assistantContent);
        assertTrue(assistantJson.has("documentType"));
        assertTrue(assistantJson.has("language"));
        assertTrue(assistantJson.has("structure"));
        assertTrue(assistantJson.has("complexity"));
    }
}
