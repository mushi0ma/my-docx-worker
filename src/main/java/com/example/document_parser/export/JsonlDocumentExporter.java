package com.example.document_parser.export;

import com.example.document_parser.dto.DocumentMetadataResponse;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.Writer;

/**
 * Экспортёр документа в JSONL (JSON Lines) формат.
 * Каждый блок документа — отдельная JSON-строка.
 * Пишем напрямую в Writer — не загружаем весь документ в RAM.
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
        for (DocumentMetadataResponse.DocumentBlock block : doc.getContentBlocks()) {
            writer.write(objectMapper.writeValueAsString(block));
            writer.write('\n');
        }
    }
}