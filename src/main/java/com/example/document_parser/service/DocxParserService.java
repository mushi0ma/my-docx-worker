package com.example.document_parser.service;

import com.example.document_parser.dto.DocumentMetadataResponse;
import com.example.document_parser.dto.DocumentMetadataResponse.*;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class DocxParserService {

    public DocumentMetadataResponse parseDocument(File file) {
        // ИСправленный блок try-with-resources
        try (InputStream is = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(is)) {

            List<DocumentBlock> blocks = new ArrayList<>();
            int manualWordCount = 0;
            int manualCharCount = 0;

            // 1. Извлекаем Header
            for (XWPFHeader header : document.getHeaderList()) {
                blocks.add(DocumentBlock.builder().type("HEADER").text(header.getText()).build());
            }

            // 2. Идем по основному телу документа
            for (IBodyElement element : document.getBodyElements()) {
                if (element.getElementType() == BodyElementType.PARAGRAPH) {
                    XWPFParagraph p = (XWPFParagraph) element;

                    // Ручной подсчет статистики
                    String text = p.getText();
                    if (!text.trim().isEmpty()) {
                        manualCharCount += text.length();
                        manualWordCount += text.split("\\s+").length;
                    }

                    blocks.addAll(processParagraph(p, document));
                } else if (element.getElementType() == BodyElementType.TABLE) {
                    blocks.add(processTable((XWPFTable) element, document));
                }
            }

            // 3. Извлекаем Сноски (Footnotes) с правильной сборкой
            for (XWPFFootnote footnote : document.getFootnotes()) {
                StringBuilder footnoteText = new StringBuilder();
                for (XWPFParagraph p : footnote.getParagraphs()) {
                    footnoteText.append(p.getText()).append("\n");
                }
                if (!footnoteText.isEmpty()) {
                    blocks.add(DocumentBlock.builder()
                            .type("FOOTNOTE")
                            .text(footnoteText.toString().trim())
                            .build());
                }
            }

            // 4. Сборка метаданных (с фоллбэком на ручной подсчет)
            DocumentStats stats = extractMetadata(document, manualWordCount, manualCharCount);

            return DocumentMetadataResponse.builder()
                    .fileName(file.getName()) // Исправлено на getName()
                    .stats(stats)
                    .contentBlocks(blocks)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Ошибка при парсинге документа: " + e.getMessage(), e);
        }
    }

    private DocumentStats extractMetadata(XWPFDocument document, int manualWordCount, int manualCharCount) {
        POIXMLProperties.CoreProperties core = document.getProperties().getCoreProperties();
        POIXMLProperties.ExtendedProperties ext = document.getProperties().getExtendedProperties();

        int words = ext.getUnderlyingProperties().getWords();
        int chars = ext.getUnderlyingProperties().getCharactersWithSpaces();
        int pages = ext.getUnderlyingProperties().getPages();

        // Если системной статы нет - берем нашу ручную и считаем страницы грубо (1800 символов = 1 страница)
        if (words == 0) words = manualWordCount;
        if (chars == 0) chars = manualCharCount;
        if (pages == 0) pages = Math.max(1, manualCharCount / 1800);

        return DocumentStats.builder()
                .author(core.getCreator() != null ? core.getCreator() : "Unknown")
                .title(core.getTitle())
                .creationDate(core.getCreated() != null ? core.getCreated().toString() : null)
                .estimatedPages(pages)
                .estimatedWords(words)
                .charactersWithSpaces(chars)
                .build();
    }

    private List<DocumentBlock> processParagraph(XWPFParagraph paragraph, XWPFDocument document) {
        List<DocumentBlock> blocks = new ArrayList<>();
        boolean hasText = !paragraph.getText().trim().isEmpty();
        if (!hasText && paragraph.getRuns().isEmpty()) return blocks;

        // ПРОВЕРКА НА БЛОК КОДА (Anti-Bloat System)
        boolean isCodeBlock = false;
        StringBuilder codeTextBuilder = new StringBuilder();

        if (hasText) {
            isCodeBlock = true; // Предполагаем, что это код, пока не докажем обратное
            for (XWPFRun run : paragraph.getRuns()) {
                if (run.getText(0) != null && !run.getText(0).trim().isEmpty()) {
                    String font = run.getFontFamily();
                    // Если находим обычный шрифт - это не блок кода
                    if (font == null || (!font.toLowerCase().contains("courier") && !font.toLowerCase().contains("consolas"))) {
                        isCodeBlock = false;
                        break;
                    }
                    codeTextBuilder.append(run.getText(0));
                }
            }
        }

        // Если это чистый код - склеиваем и отдаем!
        if (isCodeBlock && !codeTextBuilder.isEmpty()) {
            blocks.add(DocumentBlock.builder()
                    .type("CODE_BLOCK")
                    .text("```\n" + codeTextBuilder.toString() + "\n```")
                    .build());
            return blocks; // Сразу выходим, не дробим на runs!
        }

        // Если это обычный текст, идем стандартным путем
        String type = paragraph.getNumID() != null ? "LIST_ITEM" : "PARAGRAPH";
        List<RunData> runDataList = new ArrayList<>();

        for (XWPFRun run : paragraph.getRuns()) {
            String hyperlink = null;
            if (run instanceof XWPFHyperlinkRun linkRun) {
                XWPFHyperlink link = document.getHyperlinkByID(linkRun.getHyperlinkId());
                if (link != null) hyperlink = link.getURL();
            }

            if (run.getText(0) != null && !run.getText(0).trim().isEmpty()) {
                runDataList.add(RunData.builder()
                        .text(run.getText(0))
                        .fontFamily(run.getFontFamily())
                        .fontSize(run.getFontSizeAsDouble())
                        .color(run.getColor())
                        .isBold(run.isBold())
                        .isItalic(run.isItalic())
                        .hyperlink(hyperlink)
                        .build());
            }

            for (XWPFPicture picture : run.getEmbeddedPictures()) {
                XWPFPictureData picData = picture.getPictureData();
                byte[] bytes = picData.getData();
                String base64Image = Base64.getEncoder().encodeToString(bytes);
                blocks.add(DocumentBlock.builder()
                        .type("IMAGE")
                        .imageName(picData.getFileName())
                        .imageContentType(picData.getPackagePart().getContentType())
                        .imageBase64("data:" + picData.getPackagePart().getContentType() + ";base64," + base64Image)
                        .build());
            }
        }

        if (!runDataList.isEmpty() || paragraph.getNumID() != null) {
            blocks.add(DocumentBlock.builder()
                    .type(type)
                    .text(paragraph.getText())
                    .styleName(paragraph.getStyleID())
                    .alignment(paragraph.getAlignment() != null ? paragraph.getAlignment().name() : "DEFAULT")
                    .listLevel(paragraph.getNumIlvl() != null ? paragraph.getNumIlvl().toString() : null)
                    .runs(runDataList)
                    .build());
        }

        return blocks;
    }

    private DocumentBlock processTable(XWPFTable table, XWPFDocument document) {
        List<TableRowData> rowDataList = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<TableCellData> cellDataList = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                List<DocumentBlock> cellBlocks = new ArrayList<>();
                for (XWPFParagraph p : cell.getParagraphs()) {
                    cellBlocks.addAll(processParagraph(p, document));
                }
                cellDataList.add(TableCellData.builder()
                        .text(cell.getText())
                        .color(cell.getColor())
                        .cellContent(cellBlocks)
                        .build());
            }
            rowDataList.add(TableRowData.builder().cells(cellDataList).build());
        }

        return DocumentBlock.builder()
                .type("TABLE")
                .rowsCount(table.getNumberOfRows())
                .columnsCount(table.getRows().isEmpty() ? 0 : table.getRow(0).getTableCells().size())
                .tableRows(rowDataList)
                .build();
    }
}