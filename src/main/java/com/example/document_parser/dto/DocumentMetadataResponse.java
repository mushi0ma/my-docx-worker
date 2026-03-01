package com.example.document_parser.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

// ИСПРАВЛЕНО: @NoArgsConstructor + @AllArgsConstructor обязательны при @Builder.
// Без @NoArgsConstructor Jackson не может создать объект при десериализации (readValue).
// Без @AllArgsConstructor Lombok @Builder не компилируется вместе с @NoArgsConstructor.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class DocumentMetadataResponse {

    private String fileName;
    private DocumentStats stats;
    private Map<String, StyleData> documentStyles;
    private Map<String, NumberingData> documentNumbering;
    private List<DocumentBlock> contentBlocks;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class DocumentStats {
        private String author;
        private String title;
        private String creationDate;
        private String modifiedDate;
        private int estimatedPages;
        private int estimatedWords;
        private int charactersWithSpaces;
        private PageLayoutData pageLayout;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class DocumentBlock {
        private String type;
        private String text;
        private String styleName;
        private String alignment;
        private Integer indentFirstLine;
        private Integer indentLeft;
        private Integer spacingBefore;
        private Integer spacingAfter;
        private Double lineSpacing;
        private List<RunData> runs;
        private String listLevel;
        private String listNumId;
        private Integer rowsCount;
        private Integer columnsCount;
        private List<TableRowData> tableRows;
        private String imageName;
        private String imageContentType;
        private String imageUrl;
        private Integer semanticLevel;
        private String embeddedObjectName;
        private String downloadUrl;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class RunData {
        private String text;
        private String fontFamily;
        private Double fontSize;
        private String color;
        private Boolean isBold;
        private Boolean isItalic;
        private Boolean isUnderline;
        private String hyperlink;
        private String textHighlightColor;
        private Boolean isStrikeThrough;
        private Boolean isSubscript;
        private Boolean isSuperscript;
        private Boolean isInternalLink;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class TableRowData {
        private List<TableCellData> cells;
        private Integer height;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class TableCellData {
        private String text;
        private String color;
        private List<DocumentBlock> cellContent;
        private Integer width;
        private String backgroundColor;
        private Map<String, String> borders;
        private Integer colSpan;
        private String vMerge;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class StyleData {
        private String name;
        private String basedOn;
        private String type;
        private String fontFamily;
        private Double fontSize;
        private String color;
        private Boolean isBold;
        private Boolean isItalic;
        private Integer spacingBefore;
        private Integer spacingAfter;
        private Double lineSpacing;
        private Integer indentFirstLine;
        private Integer indentLeft;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class NumberingData {
        private String abstractNumId;
        private Map<String, ListLevelData> levels;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class ListLevelData {
        private String levelText;
        private String numFormat;
        private String alignment;
        private Integer indentLeft;
        private Integer indentHanging;
        private String fontFamily;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class PageLayoutData {
        private Double pageWidth;
        private Double pageHeight;
        private Double marginTop;
        private Double marginBottom;
        private Double marginLeft;
        private Double marginRight;
        private String orientation;
    }
}