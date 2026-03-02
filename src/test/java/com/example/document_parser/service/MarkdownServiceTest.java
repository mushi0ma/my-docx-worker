package com.example.document_parser.service;

import com.example.document_parser.dto.DocumentMetadataResponse;
import com.example.document_parser.dto.DocumentMetadataResponse.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownServiceTest {

    private MarkdownService markdownService;

    @BeforeEach
    void setUp() {
        markdownService = new MarkdownService();
    }

    // --- toMarkdown tests ---

    @Test
    void toMarkdown_nullBlocks_returnsFrontmatterOnly() {
        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .stats(DocumentStats.builder().title("Test").build())
                .build();

        String md = markdownService.toMarkdown(doc);

        assertTrue(md.contains("title: \"Test\""));
        assertFalse(md.contains("null"));
    }

    @Test
    void toMarkdown_emptyBlocks_returnsMinimalOutput() {
        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .contentBlocks(List.of())
                .build();

        String md = markdownService.toMarkdown(doc);

        assertNotNull(md);
    }

    @Test
    void toMarkdown_paragraphWithSemanticLevel_rendersHeading() {
        DocumentBlock block = DocumentBlock.builder()
                .type("PARAGRAPH")
                .text("Chapter 1")
                .semanticLevel(2)
                .build();

        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .contentBlocks(List.of(block))
                .build();

        String md = markdownService.toMarkdown(doc);

        assertTrue(md.contains("## "), "Should render as H2");
        assertTrue(md.contains("Chapter 1"));
    }

    @Test
    void toMarkdown_listItem_rendersWithIndent() {
        DocumentBlock block = DocumentBlock.builder()
                .type("LIST_ITEM")
                .text("Item one")
                .listLevel("2")
                .build();

        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .contentBlocks(List.of(block))
                .build();

        String md = markdownService.toMarkdown(doc);

        assertTrue(md.contains("    - "), "Should indent by 2 levels");
        assertTrue(md.contains("Item one"));
    }

    @Test
    void toMarkdown_codeBlock_notEscaped() {
        DocumentBlock block = DocumentBlock.builder()
                .type("CODE_BLOCK")
                .text("```java\nSystem.out.println(\"hello\");\n```")
                .build();

        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .contentBlocks(List.of(block))
                .build();

        String md = markdownService.toMarkdown(doc);

        assertTrue(md.contains("System.out.println"), "Code block should not be escaped");
    }

    @Test
    void toMarkdown_table_rendersMarkdownTable() {
        TableCellData cell1 = TableCellData.builder().text("Header1").build();
        TableCellData cell2 = TableCellData.builder().text("Header2").build();
        TableCellData cell3 = TableCellData.builder().text("Value1").build();
        TableCellData cell4 = TableCellData.builder().text("Value2").build();

        DocumentBlock table = DocumentBlock.builder()
                .type("TABLE")
                .tableRows(List.of(
                        TableRowData.builder().cells(List.of(cell1, cell2)).build(),
                        TableRowData.builder().cells(List.of(cell3, cell4)).build()))
                .build();

        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .contentBlocks(List.of(table))
                .build();

        String md = markdownService.toMarkdown(doc);

        assertTrue(md.contains("| Header1 |"), "Should contain header row");
        assertTrue(md.contains("---|"), "Should contain separator row");
        assertTrue(md.contains("| Value1 |"), "Should contain data row");
    }

    @Test
    void toMarkdown_image_rendersImageTag() {
        DocumentBlock block = DocumentBlock.builder()
                .type("IMAGE")
                .imageName("photo.png")
                .imageUrl("/api/v1/documents/123/images/photo.png")
                .build();

        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .contentBlocks(List.of(block))
                .build();

        String md = markdownService.toMarkdown(doc);

        assertTrue(md.contains("!["));
        assertTrue(md.contains("photo.png"));
    }

    @Test
    void toMarkdown_boldAndItalicRuns_rendersFormatting() {
        RunData boldRun = RunData.builder().text("bold text").isBold(true).build();
        RunData italicRun = RunData.builder().text("italic text").isItalic(true).build();

        DocumentBlock block = DocumentBlock.builder()
                .type("PARAGRAPH")
                .text("bold text italic text")
                .runs(List.of(boldRun, italicRun))
                .build();

        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .contentBlocks(List.of(block))
                .build();

        String md = markdownService.toMarkdown(doc);

        assertTrue(md.contains("**"), "Should contain bold markers");
        assertTrue(md.contains("*"), "Should contain italic markers");
    }

    @Test
    void toMarkdown_escapeSpecialChars() {
        DocumentBlock block = DocumentBlock.builder()
                .type("PARAGRAPH")
                .text("Price is $10 * 2 = $20")
                .build();

        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .contentBlocks(List.of(block))
                .build();

        String md = markdownService.toMarkdown(doc);

        assertTrue(md.contains("\\*"), "Asterisk should be escaped");
    }

    // --- toPlainText tests ---

    @Test
    void toPlainText_returnsCleanText() {
        DocumentBlock b1 = DocumentBlock.builder().type("PARAGRAPH").text("Hello world").build();
        DocumentBlock b2 = DocumentBlock.builder().type("PARAGRAPH").text("Second paragraph").build();

        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .contentBlocks(List.of(b1, b2))
                .build();

        String plain = markdownService.toPlainText(doc);

        assertTrue(plain.contains("Hello world"));
        assertTrue(plain.contains("Second paragraph"));
        assertFalse(plain.contains("#"), "No markdown formatting in plain text");
    }

    @Test
    void toPlainText_nullBlocks_returnsEmpty() {
        DocumentMetadataResponse doc = DocumentMetadataResponse.builder().build();
        assertEquals("", markdownService.toPlainText(doc));
    }
}
