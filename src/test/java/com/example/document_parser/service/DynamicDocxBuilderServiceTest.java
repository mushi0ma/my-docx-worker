package com.example.document_parser.service;

import com.example.document_parser.dto.DocumentMetadataResponse.DocumentBlock;
import com.example.document_parser.dto.DocumentMetadataResponse.RunData;
import com.example.document_parser.dto.DocumentMetadataResponse.TableCellData;
import com.example.document_parser.dto.DocumentMetadataResponse.TableRowData;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DynamicDocxBuilderServiceTest {

    private DynamicDocxBuilderService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new DynamicDocxBuilderService();
        // Подменяем tempStorage на JUnit TempDir чтобы не писать в /app/temp_docs
        ReflectionTestUtils.setField(service, "tempStorage", tempDir);
    }

    // ================================================================
    // safeParseColor — параметризованные тесты
    // ================================================================

    @ParameterizedTest(name = "safeParseColor(\"{0}\") → \"{1}\"")
    @CsvSource({
            "FF0000, FF0000", // valid 6-char hex
            "1a237e, 1A237E", // lowercase → uppercase
            "#1A237E, 1A237E", // strip #
            "F00, FF0000", // 3-char shorthand
            "garbage, 000000", // unknown → default black
            "'', 000000", // empty → default black
            "red, FF0000", // named color
            "blue, 0000FF", // named color
            "navy, 000080", // named color
            "gold, FFD700", // named color
            "rgb(255), 000000", // garbage format → default
    })
    void safeParseColor_variousInputs(String input, String expected) {
        assertEquals(expected, DynamicDocxBuilderService.safeParseColor(input));
    }

    @Test
    void safeParseColor_null_returnsDefault() {
        assertEquals("000000", DynamicDocxBuilderService.safeParseColor(null));
    }

    // ================================================================
    // safeParseFontSize — граничные значения
    // ================================================================

    @ParameterizedTest(name = "safeParseFontSize({0}) → {1}")
    @CsvSource({
            "14.0, 14", // normal
            "12.0, 12", // exact default
            "100.0, 72", // clamp max
            "4.0, 6", // clamp min
            "0.0, 12", // zero → default
            "-5.0, 12", // negative → default
            "12.5, 13", // rounds up
    })
    void safeParseFontSize_clamping(Double input, int expected) {
        assertEquals(expected, DynamicDocxBuilderService.safeParseFontSize(input));
    }

    @Test
    void safeParseFontSize_null_returnsDefault() {
        assertEquals(12, DynamicDocxBuilderService.safeParseFontSize(null));
    }

    // ================================================================
    // safeParseAlignment — алиасы
    // ================================================================

    @Test
    void safeParseAlignment_knownValues() {
        assertEquals(ParagraphAlignment.LEFT, DynamicDocxBuilderService.safeParseAlignment("LEFT"));
        assertEquals(ParagraphAlignment.CENTER, DynamicDocxBuilderService.safeParseAlignment("CENTER"));
        assertEquals(ParagraphAlignment.RIGHT, DynamicDocxBuilderService.safeParseAlignment("RIGHT"));
        assertEquals(ParagraphAlignment.BOTH, DynamicDocxBuilderService.safeParseAlignment("BOTH"));
    }

    @Test
    void safeParseAlignment_aliases() {
        assertEquals(ParagraphAlignment.BOTH, DynamicDocxBuilderService.safeParseAlignment("justify"));
        assertEquals(ParagraphAlignment.BOTH, DynamicDocxBuilderService.safeParseAlignment("justified"));
        // Note: "start" → valueOf("START") succeeds because ParagraphAlignment.START
        // exists as an enum constant.
        // The alias branch ("start" → LEFT) is never reached.
        assertEquals(ParagraphAlignment.START, DynamicDocxBuilderService.safeParseAlignment("start"));
        // Similarly, "end" → valueOf("END") succeeds because ParagraphAlignment.END
        // exists.
        assertEquals(ParagraphAlignment.END, DynamicDocxBuilderService.safeParseAlignment("end"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "  ", "nonsense" })
    void safeParseAlignment_invalidInput_defaultsToLeft(String input) {
        assertEquals(ParagraphAlignment.LEFT, DynamicDocxBuilderService.safeParseAlignment(input));
    }

    // ================================================================
    // buildDocumentFromBlocks — пустые данные (NullPointerException guard)
    // ================================================================

    @Test
    void buildDocumentFromBlocks_emptyList_createsEmptyDocx() throws Exception {
        File result = service.buildDocumentFromBlocks(Collections.emptyList(), "test-empty");

        assertTrue(result.exists(), "Файл должен быть создан даже для пустого списка блоков");
        assertTrue(result.length() > 0, "Файл не должен быть пустым (DOCX имеет служебную структуру)");

        // Проверим, что файл — валидный DOCX
        try (FileInputStream fis = new FileInputStream(result);
                XWPFDocument doc = new XWPFDocument(fis)) {
            assertNotNull(doc, "Документ должен открываться POI без ошибок");
        }
    }

    @Test
    void buildDocumentFromBlocks_nullBlocksInList_skipsGracefully() throws Exception {
        List<DocumentBlock> blocks = new java.util.ArrayList<>();
        blocks.add(null);
        blocks.add(DocumentBlock.builder().type("PARAGRAPH").text("Valid block").build());
        blocks.add(null);

        File result = service.buildDocumentFromBlocks(blocks, "test-nulls");

        assertTrue(result.exists());
        try (FileInputStream fis = new FileInputStream(result);
                XWPFDocument doc = new XWPFDocument(fis)) {
            // Должен быть хотя бы 1 параграф (для "Valid block")
            assertFalse(doc.getParagraphs().isEmpty(), "Документ должен содержать хотя бы один параграф");
        }
    }

    // ================================================================
    // PARAGRAPH с runs — проверка стилей (bold, italic, color, font)
    // ================================================================

    @Test
    void buildParagraph_withRuns_appliesBoldItalicColorFont() throws Exception {
        RunData titleRun = RunData.builder()
                .text("Hello World")
                .isBold(true)
                .isItalic(true)
                .isUnderline(true)
                .isStrikeThrough(true)
                .fontSize(22.0)
                .color("1A237E")
                .fontFamily("Arial")
                .build();

        DocumentBlock block = DocumentBlock.builder()
                .type("PARAGRAPH")
                .alignment("CENTER")
                .spacingAfter(200)
                .runs(List.of(titleRun))
                .build();

        File result = service.buildDocumentFromBlocks(List.of(block), "test-styles");

        try (FileInputStream fis = new FileInputStream(result);
                XWPFDocument doc = new XWPFDocument(fis)) {

            XWPFParagraph paragraph = doc.getParagraphs().get(0);
            assertEquals(ParagraphAlignment.CENTER, paragraph.getAlignment(),
                    "Alignment должен быть CENTER");

            XWPFRun run = paragraph.getRuns().get(0);
            assertEquals("Hello World", run.getText(0));
            assertTrue(run.isBold(), "Текст должен быть жирным");
            assertTrue(run.isItalic(), "Текст должен быть курсивным");
            assertEquals(UnderlinePatterns.SINGLE, run.getUnderline(), "Должно быть подчеркивание");
            assertTrue(run.isStrikeThrough(), "Должно быть зачёркивание");
            assertEquals(22, run.getFontSizeAsDouble().intValue(), "Размер шрифта = 22");
            assertEquals("1A237E", run.getColor(), "Цвет = 1A237E");
            assertEquals("Arial", run.getFontFamily(), "Шрифт = Arial");
        }
    }

    @Test
    void buildParagraph_withTextFallback_noRuns() throws Exception {
        DocumentBlock block = DocumentBlock.builder()
                .type("PARAGRAPH")
                .text("Fallback text without runs")
                .build();

        File result = service.buildDocumentFromBlocks(List.of(block), "test-text-fallback");

        try (FileInputStream fis = new FileInputStream(result);
                XWPFDocument doc = new XWPFDocument(fis)) {
            XWPFParagraph paragraph = doc.getParagraphs().get(0);
            assertEquals("Fallback text without runs", paragraph.getRuns().get(0).getText(0));
        }
    }

    // ================================================================
    // TABLE — проверка структуры и backgroundColor
    // ================================================================

    @Test
    void buildTable_createsCorrectStructure() throws Exception {
        TableCellData header1 = TableCellData.builder().text("Услуга").backgroundColor("1A237E").build();
        TableCellData header2 = TableCellData.builder().text("Цена").backgroundColor("1A237E").build();
        TableCellData header3 = TableCellData.builder().text("Срок").backgroundColor("1A237E").build();

        TableCellData cell1 = TableCellData.builder().text("Разработка").build();
        TableCellData cell2 = TableCellData.builder().text("500 000 ₸").build();
        TableCellData cell3 = TableCellData.builder().text("30 дней").build();

        TableRowData headerRow = TableRowData.builder().cells(List.of(header1, header2, header3)).build();
        TableRowData dataRow = TableRowData.builder().cells(List.of(cell1, cell2, cell3)).build();

        DocumentBlock tableBlock = DocumentBlock.builder()
                .type("TABLE")
                .tableRows(List.of(headerRow, dataRow))
                .build();

        File result = service.buildDocumentFromBlocks(List.of(tableBlock), "test-table");

        try (FileInputStream fis = new FileInputStream(result);
                XWPFDocument doc = new XWPFDocument(fis)) {
            assertEquals(1, doc.getTables().size(), "Должна быть одна таблица");
            XWPFTable table = doc.getTables().get(0);
            assertEquals(2, table.getNumberOfRows(), "Должно быть 2 ряда");
            assertEquals(3, table.getRow(0).getTableCells().size(), "Должно быть 3 колонки");
        }
    }

    @Test
    void buildTable_emptyRows_noException() throws Exception {
        DocumentBlock tableBlock = DocumentBlock.builder()
                .type("TABLE")
                .tableRows(Collections.emptyList())
                .build();

        File result = service.buildDocumentFromBlocks(List.of(tableBlock), "test-table-empty");
        assertTrue(result.exists(), "Файл должен создаться даже с пустой таблицей");
    }

    // ================================================================
    // LIST — bullet и ordered
    // ================================================================

    @Test
    void buildList_bulletItems_createsNumberedParagraphs() throws Exception {
        RunData item1 = RunData.builder().text("Первый пункт").build();
        RunData item2 = RunData.builder().text("Второй пункт").build();
        RunData item3 = RunData.builder().text("Третий пункт").build();

        DocumentBlock listBlock = DocumentBlock.builder()
                .type("LIST")
                .listNumId("bullet")
                .listLevel("0")
                .runs(List.of(item1, item2, item3))
                .build();

        File result = service.buildDocumentFromBlocks(List.of(listBlock), "test-list");

        try (FileInputStream fis = new FileInputStream(result);
                XWPFDocument doc = new XWPFDocument(fis)) {
            // Каждый list item создаёт отдельный параграф
            assertTrue(doc.getParagraphs().size() >= 3,
                    "Список из 3 пунктов должен создать минимум 3 параграфа");
        }
    }

    @Test
    void buildList_orderedFromText_createsItems() throws Exception {
        DocumentBlock listBlock = DocumentBlock.builder()
                .type("LIST")
                .listNumId("ordered")
                .text("Шаг один\nШаг два\nШаг три")
                .build();

        File result = service.buildDocumentFromBlocks(List.of(listBlock), "test-ordered-list");
        assertTrue(result.exists());

        try (FileInputStream fis = new FileInputStream(result);
                XWPFDocument doc = new XWPFDocument(fis)) {
            assertTrue(doc.getParagraphs().size() >= 3,
                    "Текстовый LIST из 3 строк должен создать 3 параграфа");
        }
    }

    // ================================================================
    // FULL INTEGRATION: PARAGRAPH + TABLE + LIST → valid .docx
    // ================================================================

    @Test
    void buildDocumentFromBlocks_fullDocument_producesValidDocx() throws Exception {
        // PARAGRAPH
        DocumentBlock paragraph = DocumentBlock.builder()
                .type("PARAGRAPH")
                .alignment("CENTER")
                .runs(List.of(
                        RunData.builder().text("КОММЕРЧЕСКОЕ ПРЕДЛОЖЕНИЕ").isBold(true)
                                .fontSize(22.0).color("1A237E").fontFamily("Arial").build()))
                .build();

        // TABLE
        TableRowData headerRow = TableRowData.builder()
                .cells(List.of(
                        TableCellData.builder().text("Услуга").backgroundColor("1A237E").build(),
                        TableCellData.builder().text("Цена").backgroundColor("1A237E").build()))
                .build();
        TableRowData dataRow = TableRowData.builder()
                .cells(List.of(
                        TableCellData.builder().text("Сайт").build(),
                        TableCellData.builder().text("500 000 ₸").build()))
                .build();
        DocumentBlock table = DocumentBlock.builder()
                .type("TABLE")
                .tableRows(List.of(headerRow, dataRow))
                .build();

        // LIST
        DocumentBlock list = DocumentBlock.builder()
                .type("LIST")
                .listNumId("bullet")
                .listLevel("0")
                .runs(List.of(
                        RunData.builder().text("Пункт 1").build(),
                        RunData.builder().text("Пункт 2").build()))
                .build();

        File result = service.buildDocumentFromBlocks(List.of(paragraph, table, list), "test-full");

        assertTrue(result.exists(), "Файл должен существовать");
        assertTrue(result.length() > 100, "Файл должен быть не пустой (> 100 байт = реальный DOCX)");

        // Открываем POI и проверяем полную структуру
        try (FileInputStream fis = new FileInputStream(result);
                XWPFDocument doc = new XWPFDocument(fis)) {
            assertFalse(doc.getParagraphs().isEmpty(), "Должны быть параграфы");
            assertEquals(1, doc.getTables().size(), "Должна быть одна таблица");

            // Title paragraph
            XWPFRun titleRun = doc.getParagraphs().get(0).getRuns().get(0);
            assertEquals("КОММЕРЧЕСКОЕ ПРЕДЛОЖЕНИЕ", titleRun.getText(0));
            assertTrue(titleRun.isBold());
        }
    }

    @Test
    void buildDocumentFromBlocks_unknownType_treatedAsParagraph() throws Exception {
        DocumentBlock block = DocumentBlock.builder()
                .type("UNKNOWN_TYPE")
                .text("Fallback paragraph")
                .build();

        File result = service.buildDocumentFromBlocks(List.of(block), "test-unknown");

        try (FileInputStream fis = new FileInputStream(result);
                XWPFDocument doc = new XWPFDocument(fis)) {
            assertEquals("Fallback paragraph", doc.getParagraphs().get(0).getRuns().get(0).getText(0),
                    "Unknown type должен обрабатываться как PARAGRAPH");
        }
    }

    // ================================================================
    // LIST — formatted runs + two lists in a row
    // ================================================================

    @Test
    void buildList_withFormattedRuns_preservesStyling() throws Exception {
        RunData boldItem = RunData.builder().text("Жирный пункт").isBold(true).color("FF0000").fontSize(14.0).build();
        RunData italicItem = RunData.builder().text("Курсивный пункт").isItalic(true).color("0000FF").build();

        DocumentBlock listBlock = DocumentBlock.builder()
                .type("LIST")
                .listNumId("bullet")
                .listLevel("0")
                .runs(List.of(boldItem, italicItem))
                .build();

        File result = service.buildDocumentFromBlocks(List.of(listBlock), "test-list-styled");

        try (FileInputStream fis = new FileInputStream(result);
                XWPFDocument doc = new XWPFDocument(fis)) {
            // Should have at least 2 paragraphs (one per list item)
            assertTrue(doc.getParagraphs().size() >= 2, "Должны быть минимум 2 параграфа для двух list items");

            XWPFRun firstRun = doc.getParagraphs().get(0).getRuns().get(0);
            assertTrue(firstRun.isBold(), "Первый пункт должен быть жирным");
            assertEquals("FF0000", firstRun.getColor(), "Первый пункт должен быть красным");
            assertEquals(14, firstRun.getFontSizeAsDouble().intValue(), "Размер шрифта = 14");

            XWPFRun secondRun = doc.getParagraphs().get(1).getRuns().get(0);
            assertTrue(secondRun.isItalic(), "Второй пункт должен быть курсивным");
            assertEquals("0000FF", secondRun.getColor(), "Второй пункт должен быть синим");
        }
    }

    @Test
    void buildList_twoListsInRow_noConflict() throws Exception {
        DocumentBlock bulletList = DocumentBlock.builder()
                .type("LIST").listNumId("bullet").listLevel("0")
                .runs(List.of(
                        RunData.builder().text("Bullet 1").build(),
                        RunData.builder().text("Bullet 2").build()))
                .build();

        DocumentBlock orderedList = DocumentBlock.builder()
                .type("LIST").listNumId("ordered").listLevel("0")
                .runs(List.of(
                        RunData.builder().text("Step 1").build(),
                        RunData.builder().text("Step 2").build()))
                .build();

        File result = service.buildDocumentFromBlocks(List.of(bulletList, orderedList), "test-two-lists");

        assertTrue(result.exists(), "Файл должен создаться");
        try (FileInputStream fis = new FileInputStream(result);
                XWPFDocument doc = new XWPFDocument(fis)) {
            assertTrue(doc.getParagraphs().size() >= 4,
                    "Два списка по 2 пункта = минимум 4 параграфа");
        }
    }
}
