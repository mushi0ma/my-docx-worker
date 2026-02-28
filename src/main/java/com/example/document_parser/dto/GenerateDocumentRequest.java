package com.example.document_parser.dto;

import lombok.Data;

@Data
public class GenerateDocumentRequest {
    private DocumentMetadataResponse metadata;
    private String webhookUrl;
    private String templateId;
}
