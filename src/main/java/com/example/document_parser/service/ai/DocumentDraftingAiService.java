package com.example.document_parser.service.ai;

import com.example.document_parser.dto.DocumentMetadataResponse.DocumentBlock;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

import java.util.List;

/**
 * LangChain4j AI Service для генерации структурированных документов.
 *
 * LangChain4j автоматически парсит ответ LLM в List<DocumentBlock>,
 * устраняя необходимость в ручном JSON parsing + stripMarkdownFences.
 */
public interface DocumentDraftingAiService {

    @SystemMessage("""
            You are an expert corporate document writer and formatter.
            You generate richly formatted Word documents as structured JSON.

            CRITICAL RULES:
            1. Return ONLY a valid JSON array of DocumentBlock objects
            2. Colors MUST be 6-char HEX without '#': "FF0000", "000000", "FFFFFF"
            3. fontSize MUST be an integer: 12, 14, 16, 24 — NOT 12.5 or "12pt"
            4. type MUST be exactly: "PARAGRAPH", "TABLE", or "LIST"
            5. alignment MUST be exactly: "LEFT", "CENTER", "RIGHT", or "BOTH"
            6. DO NOT use nested arrays where the schema does not expect them
            7. Every PARAGRAPH must have either "runs" array OR "text" field, not both

            DocumentBlock schema:
            {
              "type": "PARAGRAPH" | "TABLE" | "LIST",
              "text": "plain text fallback (optional if runs provided)",
              "alignment": "LEFT" | "CENTER" | "RIGHT" | "BOTH",
              "spacingBefore": integer (points, optional),
              "spacingAfter": integer (points, optional),
              "runs": [
                {
                  "text": "formatted text segment",
                  "isBold": true/false,
                  "isItalic": true/false,
                  "isUnderline": true/false,
                  "isStrikeThrough": true/false,
                  "fontSize": integer (e.g. 12),
                  "fontFamily": "Arial" | "Times New Roman" | etc.,
                  "color": "HEX without #" (e.g. "FF0000")
                }
              ],
              "listNumId": "bullet" | "ordered" (only for LIST type),
              "listLevel": "0" | "1" | "2" (nesting depth, only for LIST type),
              "tableRows": [ { "cells": [ { "text": "cell text" } ] } ] (only for TABLE type)
            }
            """)
    List<DocumentBlock> generateDocument(@UserMessage String prompt);

    // ============================================================
    // Multi-Agent prompts (Task 8)
    // ============================================================

    @SystemMessage("""
            You are a document planning expert.
            Given a user request, generate ONLY a JSON array of section headings (strings)
            that would form the perfect structure for this document.

            Return ONLY a raw JSON array of strings, nothing else.
            Example: ["Title", "Introduction", "Main Section", "Conclusion"]

            Generate between 3 and 10 sections depending on document complexity.
            """)
    List<String> planDocumentStructure(@UserMessage String prompt);
}
