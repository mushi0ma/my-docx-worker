package com.example.document_parser.export;

import com.example.document_parser.dto.DocumentMetadataResponse;
import com.example.document_parser.dto.DocumentMetadataResponse.DocumentBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class JsonlDocumentExporter implements DocumentExporter {

    private final ObjectMapper mapper;

    public JsonlDocumentExporter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override public String format()        { return "jsonl"; }
    @Override public String contentType()   { return "application/jsonl;charset=UTF-8"; }
    @Override public String fileExtension() { return ".jsonl"; }

    @Override
    public void export(DocumentMetadataResponse doc, String jobId, Writer writer) throws IOException {
        if (doc.getContentBlocks() == null) return;

        int idx = 0;
        for (DocumentBlock block : doc.getContentBlocks()) {
            if (block.getText() == null || block.getText().isBlank()) continue;

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("document_id", jobId);
            record.put("block_index", idx);
            record.put("prompt", buildPrompt(block, jobId));
            record.put("completion", buildCompletion(block));

            writer.write(mapper.writeValueAsString(record) + "\n");

            // Сбрасываем буфер, чтобы избежать утечек памяти
            if (idx % 50 == 0) writer.flush();
            idx++;
        }
        writer.flush();
    }

    private String buildPrompt(DocumentBlock block, String jobId) {
        String style = block.getStyleName() != null ? ", style: " + block.getStyleName() : "";
        return "Document: " + jobId + ". Extract block type: " + block.getType() + style + ". Content: " + block.getText();
    }

    private String buildCompletion(DocumentBlock block) {
        StringBuilder sb = new StringBuilder(block.getType().toLowerCase().replace("_", " "));
        if (block.getRuns() != null) {
            boolean bold = block.getRuns().stream().anyMatch(r -> Boolean.TRUE.equals(r.getIsBold()));
            boolean italic = block.getRuns().stream().anyMatch(r -> Boolean.TRUE.equals(r.getIsItalic()));
            if (bold || italic) {
                sb.append(" [");
                if (bold) sb.append("bold");
                if (bold && italic) sb.append(",");
                if (italic) sb.append("italic");
                sb.append("]");
            }
        }
        return sb.toString();
    }
}