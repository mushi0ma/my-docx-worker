package com.example.document_parser.service;

import com.example.document_parser.dto.DocumentMetadataResponse;
import com.example.document_parser.dto.DocumentMetadataResponse.*;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
public class DocxParserService {

    // Папка для хранения извлечённых картинок (монтируется как volume)
    private static final Path IMAGE_STORAGE = Paths.get(
            System.getenv().getOrDefault("IMAGE_STORAGE_PATH", "/app/images"));

    /**
     * @param file         временный файл на диске
     * @param originalName оригинальное имя файла от пользователя (не UUID!)
     * @param jobId        для формирования imageUrl
     */
    public DocumentMetadataResponse parseDocument(File file, String originalName, String jobId) {
        try (InputStream is = new FileInputStream(file);
                XWPFDocument document = new XWPFDocument(is)) {

            List<DocumentBlock> blocks = new ArrayList<>();
            int manualWordCount = 0;
            int manualCharCount = 0;

            // 1. Хедеры
            for (XWPFHeader header : document.getHeaderList()) {
                String text = header.getText();
                if (text != null && !text.isBlank()) {
                    blocks.add(DocumentBlock.builder().type("HEADER").text(text.strip()).build());
                }
            }

            // 2. Тело документа
            for (IBodyElement element : document.getBodyElements()) {
                if (element.getElementType() == BodyElementType.PARAGRAPH) {
                    XWPFParagraph p = (XWPFParagraph) element;
                    String text = p.getText();
                    if (!text.trim().isEmpty()) {
                        manualCharCount += text.length();
                        manualWordCount += text.split("\\s+").length;
                    }
                    blocks.addAll(processParagraph(p, document, jobId));
                } else if (element.getElementType() == BodyElementType.TABLE) {
                    blocks.add(processTable((XWPFTable) element, document, jobId));
                }
            }

            // 3. Сноски
            for (XWPFFootnote footnote : document.getFootnotes()) {
                StringBuilder sb = new StringBuilder();
                for (XWPFParagraph p : footnote.getParagraphs()) {
                    sb.append(p.getText()).append("\n");
                }
                String footnoteText = sb.toString().strip();
                if (!footnoteText.isEmpty()) {
                    blocks.add(DocumentBlock.builder().type("FOOTNOTE").text(footnoteText).build());
                }
            }

            DocumentStats stats = extractMetadata(document, manualWordCount, manualCharCount);

            // ИСПРАВЛЕНО: объединяем соседние CODE_BLOCK в один листинг с переносами строк
            List<DocumentBlock> mergedBlocks = mergeAdjacentCodeBlocks(blocks);

            return DocumentMetadataResponse.builder()
                    .fileName(originalName != null ? originalName : file.getName())
                    .stats(stats)
                    .documentStyles(extractStyles(document))
                    .documentNumbering(extractNumbering(document))
                    .contentBlocks(mergedBlocks)
                    .build();

        } catch (Exception e) {
            throw new RuntimeException("Ошибка при парсинге документа: " + e.getMessage(), e);
        }
    }

    private DocumentStats extractMetadata(XWPFDocument doc, int manualWords, int manualChars) {
        POIXMLProperties.CoreProperties core = doc.getProperties().getCoreProperties();
        POIXMLProperties.ExtendedProperties ext = doc.getProperties().getExtendedProperties();

        int words = ext.getUnderlyingProperties().getWords();
        int chars = ext.getUnderlyingProperties().getCharactersWithSpaces();
        int pages = ext.getUnderlyingProperties().getPages();

        if (words == 0)
            words = manualWords;
        if (chars == 0)
            chars = manualChars;
        if (pages == 0)
            pages = Math.max(1, manualChars / 1800);

        return DocumentStats.builder()
                .author(core.getCreator() != null ? core.getCreator() : "Unknown")
                .title(core.getTitle())
                .creationDate(core.getCreated() != null ? core.getCreated().toString() : null)
                .estimatedPages(pages)
                .estimatedWords(words)
                .charactersWithSpaces(chars)
                .pageLayout(extractPageLayout(doc))
                .build();
    }

    private List<DocumentBlock> processParagraph(XWPFParagraph paragraph, XWPFDocument document, String jobId) {
        List<DocumentBlock> blocks = new ArrayList<>();

        // ИСПРАВЛЕНО: trim() убирает trailing tabs и пробелы (\t\t\t\t)
        boolean hasText = !paragraph.getText().trim().isEmpty();
        if (!hasText && paragraph.getRuns().isEmpty())
            return blocks;

        if (hasText && isCodeBlock(paragraph)) {
            StringBuilder code = new StringBuilder();
            for (XWPFRun run : paragraph.getRuns()) {
                if (run.getText(0) != null) {
                    // БАГ ИСПРАВЛЕН: \u00a0 (неразрывный пробел) → обычный пробел.
                    // Word использует \u00a0 для отступов в коде — в JSON это мусор.
                    code.append(run.getText(0).replace('\u00a0', ' '));
                }
            }
            String lang = detectCodeLanguage(code.toString());
            blocks.add(DocumentBlock.builder()
                    .type("CODE_BLOCK")
                    .text("```" + lang + "\n" + code + "\n```")
                    .build());
            return blocks;
        }

        String type = paragraph.getNumID() != null ? "LIST_ITEM" : "PARAGRAPH";
        List<RunData> runDataList = new ArrayList<>();

        for (XWPFRun run : paragraph.getRuns()) {
            // Картинки
            for (XWPFPicture picture : run.getEmbeddedPictures()) {
                XWPFPictureData picData = picture.getPictureData();
                String imageUrl = saveImageAndGetUrl(picData, jobId);
                blocks.add(DocumentBlock.builder()
                        .type("IMAGE")
                        .imageName(picData.getFileName())
                        .imageContentType(picData.getPackagePart().getContentType())
                        // ИСПРАВЛЕНО: вместо base64 в JSON — URL для отдельного запроса
                        .imageUrl(imageUrl)
                        .build());
            }

            String text = run.getText(0);
            if (text == null || text.isEmpty())
                continue;

            // БАГ ИСПРАВЛЕН: заменяем \u00a0 на пробел во всех ранах.
            text = text.replace('\u00a0', ' ');

            // Убираем trailing пробелы/табы, но НЕ пробел-разделитель между словами.
            // Пробел между словами разных стилей живёт в отдельном ране.
            // Если ран = только пробел(ы) — оставляем один пробел, не пропускаем.
            if (text.isBlank()) {
                // Добавляем пробел к предыдущему рану если он есть, иначе пропускаем
                if (!runDataList.isEmpty()) {
                    RunData prev = runDataList.get(runDataList.size() - 1);
                    if (!prev.getText().endsWith(" ")) {
                        runDataList.set(runDataList.size() - 1, RunData.builder()
                                .text(prev.getText() + " ")
                                .fontFamily(prev.getFontFamily())
                                .fontSize(prev.getFontSize())
                                .color(prev.getColor())
                                .isBold(prev.getIsBold())
                                .isItalic(prev.getIsItalic())
                                .isUnderline(prev.getIsUnderline())
                                .hyperlink(prev.getHyperlink())
                                .build());
                    }
                }
                continue;
            }
            text = text.stripTrailing();

            String hyperlink = null;
            if (run instanceof XWPFHyperlinkRun linkRun) {
                XWPFHyperlink link = document.getHyperlinkByID(linkRun.getHyperlinkId());
                if (link != null)
                    hyperlink = link.getURL();
            }

            // ИСПРАВЛЕНО: Boolean вместо boolean — false → null → не попадает в JSON
            RunData currentRun = RunData.builder()
                    .text(text)
                    .fontFamily(run.getFontFamily())
                    .fontSize(run.getFontSizeAsDouble())
                    .color(run.getColor())
                    .isBold(run.isBold() ? Boolean.TRUE : null)
                    .isItalic(run.isItalic() ? Boolean.TRUE : null)
                    .isUnderline(run.getUnderline() != UnderlinePatterns.NONE ? Boolean.TRUE : null)
                    .hyperlink(hyperlink)
                    .build();

            // Run merging — ИСПРАВЛЕНО: явный builder() вместо toBuilder()
            if (!runDataList.isEmpty()) {
                RunData prev = runDataList.get(runDataList.size() - 1);
                if (canMerge(prev, currentRun)) {
                    runDataList.set(runDataList.size() - 1, RunData.builder()
                            .text(prev.getText() + currentRun.getText())
                            .fontFamily(prev.getFontFamily())
                            .fontSize(prev.getFontSize())
                            .color(prev.getColor())
                            .isBold(prev.getIsBold())
                            .isItalic(prev.getIsItalic())
                            .isUnderline(prev.getIsUnderline())
                            .hyperlink(prev.getHyperlink())
                            .build());
                    continue;
                }
            }
            runDataList.add(currentRun);
        }

        // УЛУЧШЕНО: строим текст параграфа из обработанных runs, а не из
        // paragraph.getText().
        // paragraph.getText() у POI включает \xa0, лишние символы и не совпадает с
        // runs.
        // Результат: run text и paragraph text всегда идентичны.
        String paragraphText = runDataList.stream()
                .map(RunData::getText)
                .collect(java.util.stream.Collectors.joining())
                .strip();

        if (!runDataList.isEmpty() || paragraph.getNumID() != null) {
            blocks.add(DocumentBlock.builder()
                    .type(type)
                    .text(paragraphText)
                    .styleName(paragraph.getStyleID())
                    .alignment(paragraph.getAlignment() != null ? paragraph.getAlignment().name() : null)
                    .listLevel(paragraph.getNumIlvl() != null ? paragraph.getNumIlvl().toString() : null)
                    .indentFirstLine(
                            paragraph.getIndentationFirstLine() != -1 ? paragraph.getIndentationFirstLine() : null)
                    .indentLeft(paragraph.getIndentationLeft() != -1 ? paragraph.getIndentationLeft() : null)
                    .spacingBefore(paragraph.getSpacingBefore() != -1 ? paragraph.getSpacingBefore() : null)
                    .spacingAfter(paragraph.getSpacingAfter() != -1 ? paragraph.getSpacingAfter() : null)
                    .lineSpacing(paragraph.getSpacingBetween() != -1 ? paragraph.getSpacingBetween() : null)
                    .runs(runDataList)
                    .build());
        }

        return blocks;
    }

    /**
     * Сохраняет картинку на диск и возвращает URL для получения через API.
     * ИСПРАВЛЕНО: картинки больше не кладутся base64 в основной JSON (экономия
     * 70KB+ на картинку).
     */
    /**
     * ИСПРАВЛЕНО: шардированная структура директорий.
     * Вместо /images/{jobId}/file.png → /images/ab/cd/{jobId}/file.png
     * Первые 4 символа UUID используются как 2 уровня директорий.
     * При 100k документов в одной папке Linux начинает тормозить.
     * Шардирование ограничивает до ~1500 элементов на директорию.
     *
     * ИСПРАВЛЕНО: URL не содержит хардкод /api/v1 —
     * префикс API вынесен в константу, при смене не нужно перепарсивать документы.
     */
    private static final String API_PREFIX = "/api/v1";

    // Найти в классе этот метод и заменить на:
    private String saveImageAndGetUrl(XWPFPictureData picData, String jobId) {
        try {
            String shard = jobId.replace("-", "").substring(0, 4);
            Path shardDir = IMAGE_STORAGE
                    .resolve(shard.substring(0, 2))
                    .resolve(shard.substring(2, 4))
                    .resolve(jobId);
            Files.createDirectories(shardDir);

            // ИСПРАВЛЕНИЕ: Добавляем короткий 8-значный UUID перед именем файла
            String shortId = UUID.randomUUID().toString().substring(0, 8);
            String safeFileName = shortId + "_" + picData.getFileName();

            Path imagePath = shardDir.resolve(safeFileName);
            Files.write(imagePath, picData.getData());

            return API_PREFIX + "/documents/" + jobId + "/images/" + safeFileName;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean canMerge(RunData a, RunData b) {
        return Objects.equals(a.getIsBold(), b.getIsBold())
                && Objects.equals(a.getIsItalic(), b.getIsItalic())
                && Objects.equals(a.getIsUnderline(), b.getIsUnderline())
                && Objects.equals(a.getFontFamily(), b.getFontFamily())
                && Objects.equals(a.getFontSize(), b.getFontSize())
                && Objects.equals(a.getColor(), b.getColor())
                && a.getHyperlink() == null
                && b.getHyperlink() == null;
    }

    private boolean isCodeBlock(XWPFParagraph paragraph) {
        int mono = 0, total = 0;
        for (XWPFRun run : paragraph.getRuns()) {
            String t = run.getText(0);
            if (t == null || t.trim().isEmpty())
                continue;
            total++;
            String font = run.getFontFamily();
            if (font != null && isMonospaceFont(font))
                mono++;
        }
        return total > 0 && mono == total;
    }

    private boolean isMonospaceFont(String font) {
        String f = font.toLowerCase();
        return f.contains("courier") || f.contains("consolas") || f.contains("monospace")
                || f.contains("lucida console") || f.contains("source code");
    }

    private DocumentBlock processTable(XWPFTable table, XWPFDocument document, String jobId) {
        List<TableRowData> rowDataList = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<TableCellData> cellDataList = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                List<DocumentBlock> cellBlocks = new ArrayList<>();
                for (XWPFParagraph p : cell.getParagraphs()) {
                    cellBlocks.addAll(processParagraph(p, document, jobId));
                }

                TableCellData.TableCellDataBuilder cellBuilder = TableCellData.builder()
                        .text(cell.getText().strip())
                        .color(cell.getColor())
                        .cellContent(cellBlocks.isEmpty() ? null : cellBlocks);

                CTTcPr tcPr = cell.getCTTc().getTcPr();
                if (tcPr != null) {
                    if (tcPr.getTcW() != null && tcPr.getTcW().getW() != null) {
                        try {
                            cellBuilder.width((int) Double.parseDouble(tcPr.getTcW().getW().toString()));
                        } catch (Exception ignored) {
                        }
                    }
                    if (tcPr.getShd() != null && tcPr.getShd().getFill() != null) {
                        Object fill = tcPr.getShd().getFill();
                        if (!"auto".equals(fill.toString())) {
                            cellBuilder.backgroundColor(fill.toString());
                        }
                    }
                    if (tcPr.getGridSpan() != null && tcPr.getGridSpan().getVal() != null) {
                        try {
                            cellBuilder.colSpan((int) Double.parseDouble(tcPr.getGridSpan().getVal().toString()));
                        } catch (Exception ignored) {
                        }
                    }
                    if (tcPr.getVMerge() != null) {
                        cellBuilder.vMerge(
                                tcPr.getVMerge().getVal() != null ? tcPr.getVMerge().getVal().toString() : "continue");
                    }
                    if (tcPr.getTcBorders() != null) {
                        Map<String, String> borders = new HashMap<>();
                        if (tcPr.getTcBorders().getTop() != null)
                            borders.put("top", tcPr.getTcBorders().getTop().getVal().toString());
                        if (tcPr.getTcBorders().getBottom() != null)
                            borders.put("bottom", tcPr.getTcBorders().getBottom().getVal().toString());
                        if (tcPr.getTcBorders().getLeft() != null)
                            borders.put("left", tcPr.getTcBorders().getLeft().getVal().toString());
                        if (tcPr.getTcBorders().getRight() != null)
                            borders.put("right", tcPr.getTcBorders().getRight().getVal().toString());
                        if (!borders.isEmpty())
                            cellBuilder.borders(borders);
                    }
                }
                cellDataList.add(cellBuilder.build());
            }

            TableRowData.TableRowDataBuilder rowBuilder = TableRowData.builder().cells(cellDataList);
            CTTrPr trPr = row.getCtRow().getTrPr();
            if (trPr != null) {
                try {
                    for (CTHeight h : trPr.getTrHeightList()) {
                        if (h.getVal() != null) {
                            rowBuilder.height((int) Double.parseDouble(h.getVal().toString()));
                            break;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            rowDataList.add(rowBuilder.build());
        }
        return DocumentBlock.builder()
                .type("TABLE")
                .rowsCount(table.getNumberOfRows())
                .columnsCount(table.getRows().isEmpty() ? 0 : table.getRow(0).getTableCells().size())
                .tableRows(rowDataList)
                .build();
    }

    /**
     * БАГ ИСПРАВЛЕН: CODE_BLOCK без переносов строк.
     * В Word каждая строка кода — отдельный параграф с моноширинным шрифтом.
     * Парсер создавал отдельный CODE_BLOCK для каждого параграфа,
     * поэтому весь код был склеен в одну строку без \n.
     *
     * Решение: после парсинга объединяем соседние CODE_BLOCK блоки в один,
     * вставляя \n между строками — как будто это один листинг.
     */
    private List<DocumentBlock> mergeAdjacentCodeBlocks(List<DocumentBlock> blocks) {
        List<DocumentBlock> result = new ArrayList<>();
        int i = 0;
        while (i < blocks.size()) {
            DocumentBlock current = blocks.get(i);
            if (!"CODE_BLOCK".equals(current.getType())) {
                result.add(current);
                i++;
                continue;
            }
            // Начало серии CODE_BLOCK — собираем все подряд идущие
            StringBuilder merged = new StringBuilder();
            // Убираем обрамляющие ``` которые добавляем сами
            merged.append(unwrapCodeFences(current.getText()));
            while (i + 1 < blocks.size() && "CODE_BLOCK".equals(blocks.get(i + 1).getType())) {
                i++;
                merged.append("\n").append(unwrapCodeFences(blocks.get(i).getText()));
            }
            result.add(DocumentBlock.builder()
                    .type("CODE_BLOCK")
                    .text("```\n" + merged + "\n```")
                    .build());
            i++;
        }
        return result;
    }

    private String unwrapCodeFences(String text) {
        if (text == null)
            return "";
        // Убираем ```\n в начале и \n``` в конце
        String t = text.trim();
        if (t.startsWith("```"))
            t = t.substring(3);
        if (t.endsWith("```"))
            t = t.substring(0, t.length() - 3);
        return t.strip();
    }

    /**
     * НОВОЕ: определяет язык программирования по содержимому блока кода.
     * Результат добавляется в тег ``` для синтаксической подсветки в Markdown и
     * GitHub.
     * Пример: ```javascript ... ``` вместо ``` ... ```
     */
    private String detectCodeLanguage(String code) {
        if (code == null || code.isBlank())
            return "";
        String c = code.toLowerCase();

        if (c.contains("const ") || c.contains("let ") || c.contains("console.log")
                || c.contains("document.") || c.contains("addeventlistener")
                || c.contains("getelementbyid") || c.contains("queryselector")) {
            return "javascript";
        }
        if (c.contains("<!doctype") || c.contains("<html") || c.contains("<div")
                || c.contains("<p>") || c.contains("<script") || c.contains("<head")) {
            return "html";
        }
        if (c.contains("public class") || c.contains("system.out.println")
                || c.contains("import java.") || c.contains("@override")
                || c.contains("string[] args")) {
            return "java";
        }
        if (c.contains("def ") || c.contains("import ") && c.contains("print(")
                || c.contains("elif ") || c.contains("__init__")) {
            return "python";
        }
        if (c.contains("select ") || c.contains("insert into") || c.contains("create table")) {
            return "sql";
        }
        if (c.contains("{") && (c.contains("color:") || c.contains("margin:")
                || c.contains("padding:") || c.contains("font-size:"))) {
            return "css";
        }
        if (c.trim().startsWith("{") && c.contains("\":")) {
            return "json";
        }
        if (c.contains("#!/bin/bash") || c.contains("echo ") || c.contains("sudo ")) {
            return "bash";
        }
        return "";
    }

    private Map<String, StyleData> extractStyles(XWPFDocument document) {
        Map<String, StyleData> stylesMap = new HashMap<>();
        XWPFStyles styles = document.getStyles();
        if (styles == null)
            return stylesMap;

        try {
            java.lang.reflect.Field f = styles.getClass().getDeclaredField("ctStyles");
            f.setAccessible(true);
            CTStyles ctStyles = (CTStyles) f.get(styles);
            if (ctStyles == null)
                return stylesMap;
            for (CTStyle ctStyle : ctStyles.getStyleList()) {
                String styleId = ctStyle.getStyleId();
                if (styleId == null)
                    continue;

                StyleData.StyleDataBuilder builder = StyleData.builder()
                        .name(ctStyle.getName() != null ? ctStyle.getName().getVal() : styleId)
                        .type(ctStyle.getType() != null ? ctStyle.getType().toString() : null)
                        .basedOn(ctStyle.getBasedOn() != null ? ctStyle.getBasedOn().getVal() : null);

                CTPPrGeneral ppr = ctStyle.getPPr();
                if (ppr != null) {
                    if (ppr.getSpacing() != null) {
                        try {
                            if (ppr.getSpacing().getBefore() != null)
                                builder.spacingBefore(
                                        (int) Double.parseDouble(ppr.getSpacing().getBefore().toString()));
                            if (ppr.getSpacing().getAfter() != null)
                                builder.spacingAfter((int) Double.parseDouble(ppr.getSpacing().getAfter().toString()));
                            if (ppr.getSpacing().getLine() != null)
                                builder.lineSpacing(Double.parseDouble(ppr.getSpacing().getLine().toString()) / 240.0);
                        } catch (Exception ignored) {
                        }
                    }
                    if (ppr.getInd() != null) {
                        try {
                            if (ppr.getInd().getFirstLine() != null)
                                builder.indentFirstLine(
                                        (int) Double.parseDouble(ppr.getInd().getFirstLine().toString()));
                            if (ppr.getInd().getLeft() != null)
                                builder.indentLeft((int) Double.parseDouble(ppr.getInd().getLeft().toString()));
                        } catch (Exception ignored) {
                        }
                    }
                }

                CTRPr rpr = ctStyle.getRPr();
                if (rpr != null) {
                    if (!rpr.getRFontsList().isEmpty() && rpr.getRFontsList().get(0).getAscii() != null) {
                        builder.fontFamily(rpr.getRFontsList().get(0).getAscii());
                    }
                    if (!rpr.getSzList().isEmpty()) {
                        try {
                            builder.fontSize(Double.parseDouble(rpr.getSzList().get(0).getVal().toString()) / 2.0); // half-points
                        } catch (Exception ignored) {
                        }
                    }
                    if (!rpr.getColorList().isEmpty()) {
                        builder.color(rpr.getColorList().get(0).getVal() != null
                                ? rpr.getColorList().get(0).getVal().toString()
                                : null);
                    }
                    if (!rpr.getBList().isEmpty())
                        builder.isBold(true);
                    if (!rpr.getIList().isEmpty())
                        builder.isItalic(true);
                }

                stylesMap.put(styleId, builder.build());
            }
        } catch (Exception e) {
            // ignore
        }
        return stylesMap;
    }

    private Map<String, NumberingData> extractNumbering(XWPFDocument document) {
        Map<String, NumberingData> numberingMap = new HashMap<>();
        XWPFNumbering numbering = document.getNumbering();
        if (numbering == null)
            return numberingMap;

        try {
            Map<String, CTAbstractNum> abstractNumMap = new HashMap<>();
            for (XWPFAbstractNum xabs : numbering.getAbstractNums()) {
                CTAbstractNum absNum = xabs.getCTAbstractNum();
                abstractNumMap.put(absNum.getAbstractNumId().toString(), absNum);
            }

            for (XWPFNum xnum : numbering.getNums()) {
                CTNum num = xnum.getCTNum();
                String numId = num.getNumId().toString();
                String abstractNumId = num.getAbstractNumId().getVal().toString();

                CTAbstractNum absNum = abstractNumMap.get(abstractNumId);
                if (absNum == null)
                    continue;

                Map<String, ListLevelData> levels = new HashMap<>();
                for (CTLvl lvl : absNum.getLvlList()) {
                    String ilvl = lvl.getIlvl().toString();
                    ListLevelData.ListLevelDataBuilder lvlBuilder = ListLevelData.builder();

                    if (lvl.getLvlText() != null && lvl.getLvlText().getVal() != null) {
                        lvlBuilder.levelText(lvl.getLvlText().getVal());
                    }
                    if (lvl.getNumFmt() != null && lvl.getNumFmt().getVal() != null) {
                        lvlBuilder.numFormat(lvl.getNumFmt().getVal().toString());
                    }
                    if (lvl.getLvlJc() != null && lvl.getLvlJc().getVal() != null) {
                        lvlBuilder.alignment(lvl.getLvlJc().getVal().toString());
                    }

                    if (lvl.getPPr() != null && lvl.getPPr().getInd() != null) {
                        CTInd ind = lvl.getPPr().getInd();
                        try {
                            if (ind.getLeft() != null)
                                lvlBuilder.indentLeft((int) Double.parseDouble(ind.getLeft().toString()));
                            if (ind.getHanging() != null)
                                lvlBuilder.indentHanging((int) Double.parseDouble(ind.getHanging().toString()));
                        } catch (Exception ignored) {
                        }
                    }

                    if (lvl.getRPr() != null && !lvl.getRPr().getRFontsList().isEmpty()) {
                        lvlBuilder.fontFamily(lvl.getRPr().getRFontsList().get(0).getAscii());
                    }

                    levels.put(ilvl, lvlBuilder.build());
                }

                numberingMap.put(numId, NumberingData.builder()
                        .abstractNumId(abstractNumId)
                        .levels(levels)
                        .build());
            }
        } catch (Exception e) {
            // ignore
        }
        return numberingMap;
    }

    private PageLayoutData extractPageLayout(XWPFDocument document) {
        try {
            CTSectPr sectPr = document.getDocument().getBody().getSectPr();
            if (sectPr == null)
                return null;

            PageLayoutData.PageLayoutDataBuilder builder = PageLayoutData.builder();

            CTPageSz pageSize = sectPr.getPgSz();
            if (pageSize != null) {
                try {
                    if (pageSize.getW() != null)
                        builder.pageWidth(Double.parseDouble(pageSize.getW().toString()));
                    if (pageSize.getH() != null)
                        builder.pageHeight(Double.parseDouble(pageSize.getH().toString()));
                } catch (Exception ignored) {
                }
                if (pageSize.getOrient() != null)
                    builder.orientation(pageSize.getOrient().toString());
            }

            CTPageMar margins = sectPr.getPgMar();
            if (margins != null) {
                try {
                    if (margins.getTop() != null)
                        builder.marginTop(Double.parseDouble(margins.getTop().toString()));
                    if (margins.getBottom() != null)
                        builder.marginBottom(Double.parseDouble(margins.getBottom().toString()));
                    if (margins.getLeft() != null)
                        builder.marginLeft(Double.parseDouble(margins.getLeft().toString()));
                    if (margins.getRight() != null)
                        builder.marginRight(Double.parseDouble(margins.getRight().toString()));
                } catch (Exception ignored) {
                }
            }

            return builder.build();
        } catch (Exception e) {
            return null;
        }
    }
}