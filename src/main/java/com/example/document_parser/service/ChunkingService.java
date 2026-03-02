package com.example.document_parser.service;

import com.example.document_parser.dto.DocumentMetadataResponse;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ИСПРАВЛЕНО: при пустом или null contentBlocks возвращаем пустой список
 * вместо того чтобы бросать IllegalArgumentException из Document.from("").
 * Это позволяет VectorizationService просто залогировать предупреждение
 * и продолжить работу, не роняя RabbitMQ-поток.
 */
@Service
public class ChunkingService {

    private final MarkdownService markdownService;

    private static final int MAX_CHARS_PER_CHUNK = 2000;
    private static final int MAX_OVERLAP_CHARS = 300;

    public ChunkingService(MarkdownService markdownService) {
        this.markdownService = markdownService;
    }

    public List<String> chunkDocument(DocumentMetadataResponse metadata) {
        String markdownContent = markdownService.toMarkdown(metadata);

        // Защита: пустой документ → пустой список вместо исключения
        if (markdownContent == null || markdownContent.isBlank()) {
            return Collections.emptyList();
        }

        Document document = Document.from(markdownContent);
        DocumentSplitter splitter = DocumentSplitters.recursive(MAX_CHARS_PER_CHUNK, MAX_OVERLAP_CHARS);
        List<TextSegment> segments = splitter.split(document);

        return segments.stream()
                .map(TextSegment::text)
                .collect(Collectors.toList());
    }
}