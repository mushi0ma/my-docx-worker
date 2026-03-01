package com.example.document_parser.service;

import com.example.document_parser.dto.DocumentMetadataResponse;
import com.example.document_parser.dto.DocumentMetadataResponse.*;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ИСПРАВЛЕНО: Класс был пустым заглушкой. Теперь реализован полноценный
 * конвертер.
 *
 * Конвертирует структурированный DocumentMetadataResponse в Markdown.
 * Используется в /api/v1/documents/{jobId}/markdown эндпоинте.
 */
@Service
public class MarkdownService {

    public String toMarkdown(DocumentMetadataResponse doc) {
        StringBuilder md = new StringBuilder();

        // --- Шапка документа ---
        if (doc.getStats() != null) {
            DocumentStats s = doc.getStats();
            md.append("---\n");
            if (s.getTitle() != null && !s.getTitle().isBlank()) {
                md.append("title: \"").append(escape(s.getTitle())).append("\"\n");
            }
            if (s.getAuthor() != null) {
                md.append("author: \"").append(escape(s.getAuthor())).append("\"\n");
            }
            if (s.getCreationDate() != null) {
                md.append("date: \"").append(s.getCreationDate()).append("\"\n");
            }
            md.append("---\n\n");
        }

        // --- Контент ---
        if (doc.getContentBlocks() == null)
            return md.toString();

        for (DocumentBlock block : doc.getContentBlocks()) {
            if (block == null || block.getType() == null)
                continue;
            md.append(blockToMarkdown(block));
            md.append("\n");
        }

        return md.toString().stripTrailing() + "\n";
    }

    private String blockToMarkdown(DocumentBlock block) {
        return switch (block.getType()) {
            case "HEADER" -> "# " + safeText(block) + "\n";
            case "FOOTNOTE" -> "> *Сноска: " + safeText(block) + "*\n";
            case "CODE_BLOCK" -> safeText(block) + "\n"; // Уже содержит ``` обёртку
            case "IMAGE" -> formatImage(block);
            case "TABLE" -> formatTable(block);
            case "LIST_ITEM" -> formatListItem(block);
            case "PARAGRAPH" -> formatParagraph(block);
            default -> safeText(block) + "\n";
        };
    }

    private String formatParagraph(DocumentBlock block) {
        if (block.getRuns() == null || block.getRuns().isEmpty()) {
            return safeText(block) + "\n";
        }

        if (block.getSemanticLevel() != null) {
            int level = Math.max(1, Math.min(block.getSemanticLevel(), 6));
            return "#".repeat(level) + " " + safeText(block) + "\n";
        }

        // Собираем параграф с учётом форматирования каждого рана
        StringBuilder sb = new StringBuilder();
        for (RunData run : block.getRuns()) {
            if (run.getText() == null || run.getText().isBlank())
                continue;

            String text = escape(run.getText());

            // Гиперссылка
            if (run.getHyperlink() != null) {
                sb.append("[").append(text).append("](").append(run.getHyperlink()).append(")");
                continue;
            }

            // Форматирование: bold + italic = ***text***
            if (Boolean.TRUE.equals(run.getIsBold()) && Boolean.TRUE.equals(run.getIsItalic())) {
                sb.append("***").append(text).append("***");
            } else if (Boolean.TRUE.equals(run.getIsBold())) {
                sb.append("**").append(text).append("**");
            } else if (Boolean.TRUE.equals(run.getIsItalic())) {
                sb.append("*").append(text).append("*");
            } else {
                sb.append(text);
            }
        }

        return sb.isEmpty() ? "" : sb + "\n";
    }

    private String formatListItem(DocumentBlock block) {
        // Вычисляем отступ по уровню вложенности
        int level = 0;
        try {
            if (block.getListLevel() != null)
                level = Integer.parseInt(block.getListLevel());
        } catch (NumberFormatException ignored) {
        }

        String indent = "  ".repeat(level);
        return indent + "- " + safeText(block) + "\n";
    }

    private String formatImage(DocumentBlock block) {
        // ИСПРАВЛЕНО: imageBase64 удалён из DTO, используем imageUrl
        String src = block.getImageUrl() != null ? block.getImageUrl() : "";
        String alt = block.getImageName() != null ? block.getImageName() : "image";
        return "![" + alt + "](" + src + ")\n";
    }

    private String formatTable(DocumentBlock block) {
        if (block.getTableRows() == null || block.getTableRows().isEmpty())
            return "";
        StringBuilder sb = new StringBuilder();

        boolean isHeader = true;
        for (TableRowData row : block.getTableRows()) {
            if (row.getCells() == null)
                continue;

            sb.append("|");
            for (TableCellData cell : row.getCells()) {
                sb.append(" ").append(escape(cell.getText() != null ? cell.getText() : "")).append(" |");
            }
            sb.append("\n");

            // После первой строки добавляем разделитель
            if (isHeader) {
                sb.append("|");
                for (int i = 0; i < row.getCells().size(); i++)
                    sb.append("---|");
                sb.append("\n");
                isHeader = false;
            }
        }
        return sb + "\n";
    }

    /** Безопасное получение текста блока. */
    private String safeText(DocumentBlock block) {
        return block.getText() != null ? block.getText() : "";
    }

    /** Экранирует специальные символы Markdown. */
    private String escape(String text) {
        if (text == null)
            return "";
        // Экранируем только |, чтобы не ломать таблицы; остальное — на усмотрение
        return text.replace("|", "\\|");
    }
}