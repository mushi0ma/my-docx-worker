package com.example.document_parser.service;

import com.example.document_parser.dto.DocumentMetadataResponse;
import com.example.document_parser.dto.DocumentMetadataResponse.*;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Исправления:
 * - escape() теперь экранирует все значимые Markdown-символы, а не только |
 *   Это предотвращает непреднамеренное форматирование (например жирный текст
 *   из-за * в оригинальном документе, или сломанные заголовки из-за #)
 * - Исключение из экранирования для CODE_BLOCK — код не должен экранироваться
 * - Добавлен метод для plain-text экспорта (без Markdown-разметки) — полезен
 *   для передачи в LLM где разметка только мешает
 */
@Service
public class MarkdownService {

    // Символы которые имеют специальное значение в Markdown
    // Экранируем их обратным слешем
    private static final Pattern MARKDOWN_SPECIAL_CHARS =
            Pattern.compile("([\\\\`*_{}\\[\\]()#+\\-.!|])");

    public String toMarkdown(DocumentMetadataResponse doc) {
        StringBuilder md = new StringBuilder();

        // --- Frontmatter (YAML-шапка) ---
        if (doc.getStats() != null) {
            DocumentStats s = doc.getStats();
            md.append("---\n");
            if (s.getTitle() != null && !s.getTitle().isBlank()) {
                // В YAML-значениях используем escape кавычек, а не Markdown-escape
                md.append("title: \"").append(s.getTitle().replace("\"", "\\\"")).append("\"\n");
            }
            if (s.getAuthor() != null && !s.getAuthor().isBlank()) {
                md.append("author: \"").append(s.getAuthor().replace("\"", "\\\"")).append("\"\n");
            }
            if (s.getCreationDate() != null) {
                md.append("date: \"").append(s.getCreationDate()).append("\"\n");
            }
            md.append("---\n\n");
        }

        if (doc.getContentBlocks() == null) return md.toString();

        for (DocumentBlock block : doc.getContentBlocks()) {
            if (block == null || block.getType() == null) continue;
            String rendered = blockToMarkdown(block);
            if (!rendered.isBlank()) {
                md.append(rendered).append("\n");
            }
        }

        return md.toString().stripTrailing() + "\n";
    }

    /**
     * Конвертирует документ в plain text без Markdown-разметки.
     * Полезно для передачи в LLM (меньше токенов, нет шума от символов форматирования).
     */
    public String toPlainText(DocumentMetadataResponse doc) {
        if (doc.getContentBlocks() == null) return "";

        StringBuilder sb = new StringBuilder();
        for (DocumentBlock block : doc.getContentBlocks()) {
            if (block == null || block.getType() == null) continue;
            String text = block.getText();
            if (text != null && !text.isBlank()) {
                sb.append(text).append("\n\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    private String blockToMarkdown(DocumentBlock block) {
        return switch (block.getType()) {
            case "HEADER"   -> "# " + escapeMarkdown(safeText(block)) + "\n";
            case "FOOTNOTE" -> "> *Сноска: " + escapeMarkdown(safeText(block)) + "*\n";
            // CODE_BLOCK уже содержит ``` обёртку — НЕ экранируем
            case "CODE_BLOCK" -> safeText(block) + "\n";
            case "IMAGE"    -> formatImage(block);
            case "TABLE"    -> formatTable(block);
            case "LIST_ITEM"-> formatListItem(block);
            case "PARAGRAPH"-> formatParagraph(block);
            default         -> escapeMarkdown(safeText(block)) + "\n";
        };
    }

    private String formatParagraph(DocumentBlock block) {
        // Заголовки (Heading1, Heading2 и т.д.) рендерим как ## заголовки
        if (block.getSemanticLevel() != null) {
            int level = Math.max(1, Math.min(block.getSemanticLevel(), 6));
            return "#".repeat(level) + " " + escapeMarkdown(safeText(block)) + "\n";
        }

        // Параграф без runs — просто текст
        if (block.getRuns() == null || block.getRuns().isEmpty()) {
            return escapeMarkdown(safeText(block)) + "\n";
        }

        // Параграф с runs — собираем с форматированием
        StringBuilder sb = new StringBuilder();
        for (RunData run : block.getRuns()) {
            if (run.getText() == null || run.getText().isBlank()) continue;

            String text = escapeMarkdown(run.getText());

            if (run.getHyperlink() != null) {
                // URL в гиперссылке НЕ экранируем
                sb.append("[").append(text).append("](").append(run.getHyperlink()).append(")");
            } else if (Boolean.TRUE.equals(run.getIsBold()) && Boolean.TRUE.equals(run.getIsItalic())) {
                sb.append("***").append(text).append("***");
            } else if (Boolean.TRUE.equals(run.getIsBold())) {
                sb.append("**").append(text).append("**");
            } else if (Boolean.TRUE.equals(run.getIsItalic())) {
                sb.append("*").append(text).append("*");
            } else if (Boolean.TRUE.equals(run.getIsStrikeThrough())) {
                sb.append("~~").append(text).append("~~");
            } else {
                sb.append(text);
            }
        }

        return sb.isEmpty() ? "" : sb + "\n";
    }

    private String formatListItem(DocumentBlock block) {
        int level = 0;
        try {
            if (block.getListLevel() != null)
                level = Integer.parseInt(block.getListLevel());
        } catch (NumberFormatException ignored) {}

        String indent = "  ".repeat(level);
        return indent + "- " + escapeMarkdown(safeText(block)) + "\n";
    }

    private String formatImage(DocumentBlock block) {
        String src = block.getImageUrl() != null ? block.getImageUrl() : "";
        // Alt-текст экранируем, URL — нет
        String alt = block.getImageName() != null
                ? escapeMarkdown(block.getImageName())
                : "image";
        return "![" + alt + "](" + src + ")\n";
    }

    private String formatTable(DocumentBlock block) {
        if (block.getTableRows() == null || block.getTableRows().isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        boolean isFirstRow = true;

        for (TableRowData row : block.getTableRows()) {
            if (row.getCells() == null) continue;

            sb.append("|");
            for (TableCellData cell : row.getCells()) {
                // В ячейках таблицы экранируем | но не другие символы
                // иначе жирный текст в таблице будет отображаться некорректно
                String cellText = cell.getText() != null
                        ? cell.getText().replace("|", "\\|").replace("\n", " ")
                        : "";
                sb.append(" ").append(cellText).append(" |");
            }
            sb.append("\n");

            if (isFirstRow) {
                sb.append("|");
                for (int i = 0; i < row.getCells().size(); i++) sb.append("---|");
                sb.append("\n");
                isFirstRow = false;
            }
        }

        return sb + "\n";
    }

    private String safeText(DocumentBlock block) {
        return block.getText() != null ? block.getText() : "";
    }

    /**
     * Экранирует специальные символы Markdown.
     *
     * ИСПРАВЛЕНО: раньше экранировались только |
     * Теперь экранируются все значимые символы: * _ # [ ] ( ) ` \ + - . ! { }
     *
     * Пример: "Hello *World*" → "Hello \*World\*"
     * Без этого текст с * будет случайно отображаться как курсив.
     *
     * ВАЖНО: не вызывать этот метод на уже готовом Markdown (CODE_BLOCK, ссылки)
     */
    private String escapeMarkdown(String text) {
        if (text == null) return "";
        return MARKDOWN_SPECIAL_CHARS.matcher(text).replaceAll("\\\\$1");
    }
}