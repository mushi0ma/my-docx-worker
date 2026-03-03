package com.example.document_parser.service;

import com.example.document_parser.dto.DocumentMetadataResponse.DocumentBlock;
import com.example.document_parser.dto.DocumentMetadataResponse.RunData;
import com.example.document_parser.dto.DocumentMetadataResponse.TableCellData;
import com.example.document_parser.dto.DocumentMetadataResponse.TableRowData;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class DynamicDocxBuilderService {

    private static final Logger log = LoggerFactory.getLogger(DynamicDocxBuilderService.class);

    private final Path tempStorage = Paths.get(System.getenv().getOrDefault("TEMP_DOCS_PATH", "/app/temp_docs"));

    private static final int DEFAULT_FONT_SIZE = 12;
    private static final String DEFAULT_COLOR = "000000";
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("^[0-9A-Fa-f]{6}$");

    /**
     * Named CSS colors → HEX mapping for AI models that send color names.
     */
    private static final Map<String, String> NAMED_COLORS = Map.ofEntries(
            Map.entry("black", "000000"), Map.entry("white", "FFFFFF"),
            Map.entry("red", "FF0000"), Map.entry("green", "008000"),
            Map.entry("blue", "0000FF"), Map.entry("yellow", "FFFF00"),
            Map.entry("orange", "FFA500"), Map.entry("purple", "800080"),
            Map.entry("pink", "FFC0CB"), Map.entry("gray", "808080"),
            Map.entry("grey", "808080"), Map.entry("brown", "A52A2A"),
            Map.entry("cyan", "00FFFF"), Map.entry("magenta", "FF00FF"),
            Map.entry("navy", "000080"), Map.entry("teal", "008080"),
            Map.entry("maroon", "800000"), Map.entry("olive", "808000"),
            Map.entry("silver", "C0C0C0"), Map.entry("lime", "00FF00"),
            Map.entry("aqua", "00FFFF"), Map.entry("gold", "FFD700"),
            Map.entry("coral", "FF7F50"), Map.entry("indigo", "4B0082"),
            Map.entry("violet", "EE82EE"), Map.entry("darkblue", "00008B"),
            Map.entry("darkgreen", "006400"), Map.entry("darkred", "8B0000"));

    // ================================================================
    // PUBLIC API
    // ================================================================

    public File buildDocumentFromBlocks(List<DocumentBlock> blocks, String optionalJobId) throws Exception {
        String jobId = optionalJobId != null ? optionalJobId : UUID.randomUUID().toString();
        File outputFile = tempStorage.resolve("generated_" + jobId + ".docx").toFile();

        try (XWPFDocument document = new XWPFDocument()) {

            for (DocumentBlock block : blocks) {
                if (block == null)
                    continue;
                String type = block.getType() != null ? block.getType().toUpperCase() : "PARAGRAPH";

                switch (type) {
                    case "PARAGRAPH" -> buildParagraph(document, block);
                    case "TABLE" -> buildTable(document, block);
                    case "LIST" -> buildList(document, block);
                    default -> {
                        log.warn("Unknown block type '{}', treating as PARAGRAPH", type);
                        buildParagraph(document, block);
                    }
                }
            }

            try (FileOutputStream out = new FileOutputStream(outputFile)) {
                document.write(out);
            }

            log.info("✅ Документ успешно сгенерирован: {}", outputFile.getName());
            return outputFile;
        } catch (Exception e) {
            log.error("❌ Ошибка при генерации документа: {}", e.getMessage(), e);
            throw e;
        }
    }

    // ================================================================
    // PARAGRAPH
    // ================================================================

    private void buildParagraph(XWPFDocument document, DocumentBlock block) {
        XWPFParagraph paragraph = document.createParagraph();
        applyParagraphProperties(paragraph, block);
        fillParagraphContent(paragraph, block);
    }

    private void applyParagraphProperties(XWPFParagraph paragraph, DocumentBlock block) {
        paragraph.setAlignment(safeParseAlignment(block.getAlignment()));

        if (block.getSpacingBefore() != null) {
            paragraph.setSpacingBefore(block.getSpacingBefore());
        }
        if (block.getSpacingAfter() != null) {
            paragraph.setSpacingAfter(block.getSpacingAfter());
        }
        if (block.getIndentFirstLine() != null) {
            paragraph.setFirstLineIndent(block.getIndentFirstLine());
        }
    }

    private void fillParagraphContent(XWPFParagraph paragraph, DocumentBlock block) {
        if (block.getRuns() != null && !block.getRuns().isEmpty()) {
            for (RunData runData : block.getRuns()) {
                if (runData == null)
                    continue;
                applyRun(paragraph.createRun(), runData);
            }
        } else if (block.getText() != null && !block.getText().isBlank()) {
            XWPFRun run = paragraph.createRun();
            run.setText(block.getText());
        }
    }

    // ================================================================
    // RUN (text segment with formatting)
    // ================================================================

    private void applyRun(XWPFRun run, RunData runData) {
        if (runData.getText() != null) {
            run.setText(runData.getText());
        }

        if (Boolean.TRUE.equals(runData.getIsBold()))
            run.setBold(true);
        if (Boolean.TRUE.equals(runData.getIsItalic()))
            run.setItalic(true);
        if (Boolean.TRUE.equals(runData.getIsUnderline()))
            run.setUnderline(UnderlinePatterns.SINGLE);
        if (Boolean.TRUE.equals(runData.getIsStrikeThrough()))
            run.setStrikeThrough(true);

        if (runData.getColor() != null && !runData.getColor().isBlank()) {
            run.setColor(safeParseColor(runData.getColor()));
        }

        run.setFontSize(safeParseFontSize(runData.getFontSize()));

        if (runData.getFontFamily() != null && !runData.getFontFamily().isBlank()) {
            run.setFontFamily(runData.getFontFamily());
        }

        // Subscript / Superscript
        if (Boolean.TRUE.equals(runData.getIsSubscript())) {
            run.setSubscript(VerticalAlign.SUBSCRIPT);
        } else if (Boolean.TRUE.equals(runData.getIsSuperscript())) {
            run.setSubscript(VerticalAlign.SUPERSCRIPT);
        }

        // Highlight
        if (runData.getTextHighlightColor() != null && !runData.getTextHighlightColor().isBlank()) {
            try {
                STHighlightColor.Enum hlColor = STHighlightColor.Enum.forString(
                        runData.getTextHighlightColor().toLowerCase());
                if (hlColor != null) {
                    run.getCTR().addNewRPr().addNewHighlight().setVal(hlColor);
                }
            } catch (Exception e) {
                log.debug("Unsupported highlight color: {}", runData.getTextHighlightColor());
            }
        }
    }

    // ================================================================
    // TABLE
    // ================================================================

    private void buildTable(XWPFDocument document, DocumentBlock block) {
        if (block.getTableRows() == null || block.getTableRows().isEmpty())
            return;

        int rowCount = block.getTableRows().size();
        int colCount = block.getTableRows().get(0).getCells() != null
                ? block.getTableRows().get(0).getCells().size()
                : 0;
        if (colCount == 0)
            return;

        XWPFTable table = document.createTable(rowCount, colCount);
        table.setWidth("100%");

        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            TableRowData rowData = block.getTableRows().get(rowIndex);
            if (rowData == null || rowData.getCells() == null)
                continue;

            XWPFTableRow row = table.getRow(rowIndex);

            for (int colIndex = 0; colIndex < rowData.getCells().size(); colIndex++) {
                TableCellData cellData = rowData.getCells().get(colIndex);
                if (cellData == null)
                    continue;

                XWPFTableCell cell = row.getCell(colIndex);

                // Clear default empty paragraph
                if (!cell.getParagraphs().isEmpty()) {
                    cell.removeParagraph(0);
                }

                // Fill cell content
                if (cellData.getCellContent() != null) {
                    for (DocumentBlock cellBlock : cellData.getCellContent()) {
                        if (cellBlock == null)
                            continue;
                        if ("PARAGRAPH".equalsIgnoreCase(cellBlock.getType())) {
                            XWPFParagraph p = cell.addParagraph();
                            p.setAlignment(safeParseAlignment(cellBlock.getAlignment()));

                            if (cellBlock.getRuns() != null && !cellBlock.getRuns().isEmpty()) {
                                for (RunData rd : cellBlock.getRuns()) {
                                    if (rd == null)
                                        continue;
                                    applyRun(p.createRun(), rd);
                                }
                            } else if (cellBlock.getText() != null) {
                                XWPFRun r = p.createRun();
                                r.setText(cellBlock.getText());
                            }
                        }
                    }
                } else if (cellData.getText() != null) {
                    XWPFParagraph p = cell.addParagraph();
                    XWPFRun r = p.createRun();
                    r.setText(cellData.getText());
                }

                // Background color
                if (cellData.getBackgroundColor() != null) {
                    cell.setColor(safeParseColor(cellData.getBackgroundColor()));
                }
            }
        }
    }

    // ================================================================
    // LIST (Task 7)
    // ================================================================

    private void buildList(XWPFDocument document, DocumentBlock block) {
        if (block.getRuns() == null && block.getText() == null)
            return;

        // Determine list style. "listNumId" from AI tells us if it's ordered or bullet.
        // Default to bullet list.
        boolean isOrdered = "ordered".equalsIgnoreCase(block.getListNumId())
                || "number".equalsIgnoreCase(block.getListNumId())
                || "numbered".equalsIgnoreCase(block.getListNumId());

        // Parse list level (0-based), default to 0
        int level = 0;
        if (block.getListLevel() != null) {
            try {
                level = Integer.parseInt(block.getListLevel());
            } catch (NumberFormatException ignored) {
            }
        }

        // Collect list items: either from runs (one item per run) or split text by
        // newlines
        List<String> items;
        if (block.getRuns() != null && !block.getRuns().isEmpty()) {
            items = block.getRuns().stream()
                    .filter(r -> r != null && r.getText() != null && !r.getText().isBlank())
                    .map(RunData::getText)
                    .toList();
        } else {
            items = List.of(block.getText().split("\\n"));
        }

        // Create numbering definition
        BigInteger abstractNumId = createAbstractNum(document, isOrdered);
        BigInteger numId = addNum(document, abstractNumId);

        for (String itemText : items) {
            if (itemText == null || itemText.isBlank())
                continue;

            // Strip manually typed "1. " or "- " prefixes if AI added them
            String cleanText = itemText.replaceFirst("^\\s*(?:\\d+[.)\\s]+|[-•*]\\s+)", "");

            XWPFParagraph paragraph = document.createParagraph();
            paragraph.setAlignment(safeParseAlignment(block.getAlignment()));

            // Set numbering properties via low-level XML
            CTNumPr numPr = paragraph.getCTP().addNewPPr().addNewNumPr();
            numPr.addNewIlvl().setVal(BigInteger.valueOf(level));
            numPr.addNewNumId().setVal(numId);

            XWPFRun run = paragraph.createRun();
            run.setText(cleanText);
            run.setFontSize(safeParseFontSize(null));
        }
    }

    /**
     * Creates an abstract numbering definition (bullet or decimal).
     */
    private BigInteger createAbstractNum(XWPFDocument document, boolean isOrdered) {
        XWPFNumbering numbering = document.createNumbering();

        CTAbstractNum abstractNum = CTAbstractNum.Factory.newInstance();
        // Use a unique ID based on existing count
        BigInteger abstractNumId = BigInteger.valueOf(
                numbering.getAbstractNums() != null ? numbering.getAbstractNums().size() : 0);
        abstractNum.setAbstractNumId(abstractNumId);

        // Define level 0
        CTLvl lvl = abstractNum.addNewLvl();
        lvl.setIlvl(BigInteger.ZERO);

        if (isOrdered) {
            lvl.addNewNumFmt().setVal(STNumberFormat.DECIMAL);
            lvl.addNewLvlText().setVal("%1.");
            lvl.addNewStart().setVal(BigInteger.ONE);
        } else {
            lvl.addNewNumFmt().setVal(STNumberFormat.BULLET);
            lvl.addNewLvlText().setVal("•");

            // Set bullet font
            CTFonts fonts = lvl.addNewRPr().addNewRFonts();
            fonts.setAscii("Symbol");
            fonts.setHAnsi("Symbol");
        }

        // Set indentation
        CTInd ind = lvl.addNewPPr().addNewInd();
        ind.setLeft(BigInteger.valueOf(720));
        ind.setHanging(BigInteger.valueOf(360));

        numbering.addAbstractNum(new XWPFAbstractNum(abstractNum));
        return abstractNumId;
    }

    /**
     * Binds an abstract numbering to a concrete num instance.
     */
    private BigInteger addNum(XWPFDocument document, BigInteger abstractNumId) {
        XWPFNumbering numbering = document.getNumbering();
        if (numbering == null) {
            numbering = document.createNumbering();
        }
        return numbering.addNum(abstractNumId);
    }

    // ================================================================
    // SAFE PARSERS (Task 6)
    // ================================================================

    /**
     * Converts any color string (named, hex, #hex, garbage) to a valid 6-char HEX
     * string.
     */
    static String safeParseColor(String color) {
        if (color == null || color.isBlank())
            return DEFAULT_COLOR;

        String cleaned = color.trim().replace("#", "");

        // Check named colors
        String named = NAMED_COLORS.get(cleaned.toLowerCase());
        if (named != null)
            return named;

        // Valid 6-char hex
        if (HEX_COLOR_PATTERN.matcher(cleaned).matches())
            return cleaned.toUpperCase();

        // 3-char shorthand → 6-char (#F00 → FF0000)
        if (cleaned.length() == 3 && cleaned.matches("[0-9A-Fa-f]{3}")) {
            return ("" + cleaned.charAt(0) + cleaned.charAt(0)
                    + cleaned.charAt(1) + cleaned.charAt(1)
                    + cleaned.charAt(2) + cleaned.charAt(2)).toUpperCase();
        }

        log.debug("Unrecognized color '{}', defaulting to black", color);
        return DEFAULT_COLOR;
    }

    /**
     * Safely converts a Double fontSize to an int, clamping to valid range [6..72].
     */
    static int safeParseFontSize(Double size) {
        if (size == null || size <= 0)
            return DEFAULT_FONT_SIZE;
        int fontSize = (int) Math.round(size);
        return Math.max(6, Math.min(72, fontSize));
    }

    /**
     * Parses alignment string, defaults to LEFT on invalid input.
     */
    static ParagraphAlignment safeParseAlignment(String alignment) {
        if (alignment == null || alignment.isBlank())
            return ParagraphAlignment.LEFT;
        try {
            return ParagraphAlignment.valueOf(alignment.toUpperCase());
        } catch (IllegalArgumentException e) {
            // Try common aliases
            return switch (alignment.toLowerCase()) {
                case "justify", "justified" -> ParagraphAlignment.BOTH;
                case "start" -> ParagraphAlignment.LEFT;
                case "end" -> ParagraphAlignment.RIGHT;
                default -> ParagraphAlignment.LEFT;
            };
        }
    }
}