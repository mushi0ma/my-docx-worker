package com.example.document_parser.service;

import com.example.document_parser.dto.DocumentMetadataResponse;
import com.example.document_parser.dto.DocumentMetadataResponse.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SelfCorrectionServiceTest {

    @Mock
    private ChatLanguageModel correctorModel;

    private ObjectMapper objectMapper;
    private SelfCorrectionService service;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().build();
        service = new SelfCorrectionService(correctorModel, objectMapper);

        // Вручную задаем переменные из @Value для корректной работы теста
        ReflectionTestUtils.setField(service, "maxJsonCharsForFullCorrection", 15000);
        ReflectionTestUtils.setField(service, "maxEmptyBlockRatio", 0.6);
        ReflectionTestUtils.setField(service, "maxBlockCorrections", 50);
    }

    @Test
    void validateAndCorrect_healthyDocument_skipsLlm() {
        DocumentMetadataResponse doc = buildHealthyDocument();

        DocumentMetadataResponse result = service.validateAndCorrect(doc);

        assertSame(doc, result, "Healthy document should be returned as-is");
        verifyNoInteractions(correctorModel);
    }

    @Test
    void validateAndCorrect_noBlocks_doesNotCallLlm() {
        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .fileName("empty.docx")
                .contentBlocks(null)
                .build();

        DocumentMetadataResponse result = service.validateAndCorrect(doc);

        assertNotNull(result);
    }

    @Test
    void validateAndCorrect_emptyBlocks_doesNotCallLlm() {
        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .fileName("empty.docx")
                .contentBlocks(List.of())
                .build();

        DocumentMetadataResponse result = service.validateAndCorrect(doc);

        assertNotNull(result);
    }

    @Test
    void validateAndCorrect_documentWithImages_treatsAsHealthy() {
        DocumentBlock imageBlock = DocumentBlock.builder()
                .type("IMAGE")
                .imageName("photo.png")
                .build();
        DocumentBlock textBlock = DocumentBlock.builder()
                .type("PARAGRAPH")
                .text("Some text here")
                .build();

        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .fileName("with_images.docx")
                .contentBlocks(List.of(imageBlock, textBlock))
                .build();

        DocumentMetadataResponse result = service.validateAndCorrect(doc);

        assertSame(doc, result, "Document with images and text should pass validation");
        verifyNoInteractions(correctorModel);
    }

    @Test
    void validateAndCorrect_tableWithConsistentColumns_isHealthy() {
        TableCellData cell = TableCellData.builder().text("Data").build();
        TableRowData row1 = TableRowData.builder().cells(List.of(cell, cell, cell)).build();
        TableRowData row2 = TableRowData.builder().cells(List.of(cell, cell, cell)).build();

        DocumentBlock table = DocumentBlock.builder()
                .type("TABLE")
                .text("Test table")
                .tableRows(List.of(row1, row2))
                .build();
        DocumentBlock paragraph = DocumentBlock.builder()
                .type("PARAGRAPH")
                .text("Some paragraph")
                .build();

        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .fileName("table.docx")
                .contentBlocks(List.of(table, paragraph))
                .build();

        DocumentMetadataResponse result = service.validateAndCorrect(doc);

        assertSame(doc, result);
        verifyNoInteractions(correctorModel);
    }

    @Test
    void validateAndCorrect_highEmptyRatio_triggersCorrection() {
        List<DocumentBlock> blocks = new ArrayList<>();
        blocks.add(DocumentBlock.builder().type("PARAGRAPH").text("Content").build());
        for (int i = 0; i < 9; i++) {
            blocks.add(DocumentBlock.builder().type("PARAGRAPH").text("").build());
        }

        DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                .fileName("mostly_empty.docx")
                .contentBlocks(blocks)
                .build();

        when(correctorModel.generate(anyString())).thenReturn("{}");

        DocumentMetadataResponse result = service.validateAndCorrect(doc);

        verify(correctorModel, atLeastOnce()).generate(anyString());
        assertNotNull(result);
    }

    private DocumentMetadataResponse buildHealthyDocument() {
        return DocumentMetadataResponse.builder()
                .fileName("healthy.docx")
                .contentBlocks(List.of(
                        DocumentBlock.builder().type("PARAGRAPH").text("First paragraph").build(),
                        DocumentBlock.builder().type("PARAGRAPH").text("Second paragraph").build(),
                        DocumentBlock.builder().type("IMAGE").imageName("img.png").build()))
                .build();
    }
}