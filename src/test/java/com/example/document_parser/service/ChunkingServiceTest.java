package com.example.document_parser.service;

import com.example.document_parser.dto.DocumentMetadataResponse;
import com.example.document_parser.dto.DocumentMetadataResponse.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChunkingServiceTest {

        private ChunkingService chunkingService;

        @BeforeEach
        void setUp() {
                MarkdownService markdownService = new MarkdownService();
                chunkingService = new ChunkingService(markdownService);
        }

        @Test
        void chunkDocument_nullBlocks_throwsOnBlankContent() {
                DocumentMetadataResponse doc = DocumentMetadataResponse.builder().build();

                // LangChain4j's Document.from() requires non-blank text
                assertThrows(IllegalArgumentException.class, () -> chunkingService.chunkDocument(doc));
        }

        @Test
        void chunkDocument_smallDocument_returnsSingleChunk() {
                DocumentBlock block = DocumentBlock.builder()
                                .type("PARAGRAPH")
                                .text("A short paragraph that fits in one chunk.")
                                .build();

                DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                                .contentBlocks(List.of(block))
                                .build();

                List<String> chunks = chunkingService.chunkDocument(doc);

                assertNotNull(chunks);
                assertFalse(chunks.isEmpty(), "Should have at least one chunk");
        }

        @Test
        void chunkDocument_largeDocument_returnsMultipleChunks() {
                // Create a document large enough to require multiple chunks
                // MAX_CHARS_PER_CHUNK is 2000, so ~5000 chars should create ~3 chunks
                StringBuilder longText = new StringBuilder();
                for (int i = 0; i < 100; i++) {
                        longText.append("This is sentence number ").append(i)
                                        .append(" in this very long paragraph. ");
                }

                DocumentBlock block = DocumentBlock.builder()
                                .type("PARAGRAPH")
                                .text(longText.toString())
                                .build();

                DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                                .contentBlocks(List.of(block))
                                .build();

                List<String> chunks = chunkingService.chunkDocument(doc);

                assertTrue(chunks.size() > 1, "Large document should produce multiple chunks, got: " + chunks.size());
        }

        @Test
        void chunkDocument_preservesContent() {
                String originalText = "Important content that must be preserved";
                DocumentBlock block = DocumentBlock.builder()
                                .type("PARAGRAPH")
                                .text(originalText)
                                .build();

                DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                                .contentBlocks(List.of(block))
                                .build();

                List<String> chunks = chunkingService.chunkDocument(doc);

                String joined = String.join("", chunks);
                assertTrue(joined.contains("Important content"), "Original text should be preserved in chunks");
        }

        @Test
        void chunkDocument_multipleBlocks_chunksAll() {
                DocumentBlock h1 = DocumentBlock.builder()
                                .type("PARAGRAPH").text("Chapter 1").semanticLevel(1).build();
                DocumentBlock p1 = DocumentBlock.builder()
                                .type("PARAGRAPH").text("Paragraph under chapter 1").build();
                DocumentBlock h2 = DocumentBlock.builder()
                                .type("PARAGRAPH").text("Chapter 2").semanticLevel(1).build();
                DocumentBlock p2 = DocumentBlock.builder()
                                .type("PARAGRAPH").text("Paragraph under chapter 2").build();

                DocumentMetadataResponse doc = DocumentMetadataResponse.builder()
                                .contentBlocks(List.of(h1, p1, h2, p2))
                                .build();

                List<String> chunks = chunkingService.chunkDocument(doc);

                assertNotNull(chunks);
                assertFalse(chunks.isEmpty());

                String all = String.join(" ", chunks);
                assertTrue(all.contains("Chapter 1"));
                assertTrue(all.contains("Chapter 2"));
        }
}
