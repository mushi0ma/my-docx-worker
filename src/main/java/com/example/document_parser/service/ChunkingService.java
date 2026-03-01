package com.example.document_parser.service;

import com.example.document_parser.dto.DocumentMetadataResponse;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChunkingService {

    private final MarkdownService markdownService;

    // Default configuration for chunking based on characters
    private static final int MAX_CHARS_PER_CHUNK = 2000;
    private static final int MAX_OVERLAP_CHARS = 300;

    public ChunkingService(MarkdownService markdownService) {
        this.markdownService = markdownService;
    }

    /**
     * Конвертирует документ в Markdown и производит "умное" чанкирование.
     * DocumentSplitters.recursive в Langchain4j отлично работает с Markdown,
     * стараясь резать по заголовкам (##, ###), параграфам и предложениям.
     */
    public List<String> chunkDocument(DocumentMetadataResponse metadata) {
        String markdownContent = markdownService.toMarkdown(metadata);

        Document document = Document.from(markdownContent);

        DocumentSplitter splitter = DocumentSplitters.recursive(MAX_CHARS_PER_CHUNK, MAX_OVERLAP_CHARS);

        List<TextSegment> segments = splitter.split(document);

        return segments.stream()
                .map(TextSegment::text)
                .collect(Collectors.toList());
    }
}
