package com.example.document_parser.export;

import com.example.document_parser.dto.DocumentMetadataResponse;
import com.example.document_parser.service.MarkdownService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Writer;

@Component
public class MarkdownDocumentExporter implements DocumentExporter {

    private final MarkdownService markdownService;

    public MarkdownDocumentExporter(MarkdownService markdownService) {
        this.markdownService = markdownService;
    }

    @Override public String format()        { return "markdown"; }
    @Override public String contentType()   { return "text/markdown;charset=UTF-8"; }
    @Override public String fileExtension() { return ".md"; }

    @Override
    public void export(DocumentMetadataResponse doc, String jobId, Writer writer) throws IOException {
        writer.write(markdownService.toMarkdown(doc));
        writer.flush();
    }
}