package com.example.document_parser.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL) // Прячем поля, равные null
public class DocumentMetadataResponse {
    private String fileName;
    private DocumentStats stats;
    private List<DocumentBlock> contentBlocks; // Теперь это универсальные блоки

    @Data
    @Builder
    public static class DocumentStats {
        private String author;
        private String title;
        private String creationDate;
        private String modifiedDate;
        private int estimatedPages;
        private int estimatedWords;
        private int charactersWithSpaces;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DocumentBlock {
        private String type; // PARAGRAPH, TABLE, LIST, IMAGE

        // Поля для PARAGRAPH и LIST
        private String text;
        private String styleName;
        private String alignment;
        private List<RunData> runs;

        // Специфично для LIST
        private String listLevel;

        // Специфично для TABLE
        private Integer rowsCount;
        private Integer columnsCount;
        private List<TableRowData> tableRows;

        // Специфично для IMAGE
        private String imageName;
        private String imageContentType;
        private String imageBase64;
    }

    @Data
    @Builder
    public static class RunData {
        private String text;
        private String fontFamily;
        private Double fontSize;
        private String color;
        private boolean isBold;
        private boolean isItalic;
        private String hyperlink;
    }

    @Data
    @Builder
    public static class TableRowData {
        private List<TableCellData> cells;
    }

    @Data
    @Builder
    public static class TableCellData {
        private String text;
        private String color; // Цвет фона ячейки
        private List<DocumentBlock> cellContent; // Ячейка может содержать абзацы
    }
}