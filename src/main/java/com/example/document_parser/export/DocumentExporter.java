package com.example.document_parser.export;

import com.example.document_parser.dto.DocumentMetadataResponse;
import java.io.IOException;
import java.io.Writer;

public interface DocumentExporter {
    String format();
    String contentType();
    String fileExtension();
    // Пишем напрямую в поток, не загружая строки в RAM
    void export(DocumentMetadataResponse doc, String jobId, Writer writer) throws IOException;
}