package com.example.document_parser.service;

import com.example.document_parser.dto.DocumentMetadataResponse;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.util.Units;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@Service
public class DocxGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(DocxGeneratorService.class);

    @Value("${app.upload.dir:/tmp/docs}")
    private String uploadDir;

    // ОБНОВЛЕННЫЙ МЕТОД: Автоматически ищет base_template.docx в ресурсах
    public File generateDocument(DocumentMetadataResponse metadata, String jobId) throws Exception {
        ClassPathResource resource = new ClassPathResource("base_template.docx");
        InputStream templateStream = resource.exists() ? resource.getInputStream() : null;

        if (templateStream == null) {
            log.warn("base_template.docx не найден в src/main/resources/. Используется пустой документ.");
        } else {
            log.info("Используется шаблон base_template.docx");
        }

        return generateDocument(metadata, jobId, templateStream);
    }

    public File generateDocument(DocumentMetadataResponse metadata, String jobId, InputStream templateStream)
            throws Exception {
        log.info("Starting generation of document for job: {}", jobId);

        try (XWPFDocument document = templateStream != null ? new XWPFDocument(templateStream) : new XWPFDocument()) {

            // ХАК ДЛЯ ШАБЛОНА: Пустой Word файл всегда содержит один пустой параграф.
            // Мы удаляем его, чтобы наш текст не начинался со второй строки.
            if (templateStream != null && document.getParagraphs().size() == 1) {
                XWPFParagraph defaultParagraph = document.getParagraphs().getFirst();
                if (defaultParagraph.getText().isEmpty()) {
                    document.removeBodyElement(document.getPosOfParagraph(defaultParagraph));
                }
            }

            // 1. Setup Global Styles, Page Layout, and Numbering
            // Если мы используем шаблон (templateStream != null), эти методы пропускаются,
            // так как шаблон УЖЕ содержит идеальные стили и размеры страницы!
            if (templateStream == null) {
                applyPageLayout(document, metadata.getStats());
                applyStyles(document, metadata.getDocumentStyles());
                applyNumbering(document, metadata.getDocumentNumbering());
            }

            // 2. Iterate content blocks and generate paragraphs/tables
            if (metadata.getContentBlocks() != null) {
                for (DocumentMetadataResponse.DocumentBlock block : metadata.getContentBlocks()) {
                    // Skip header/footer blocks from main document body
                    if ("HEADER".equalsIgnoreCase(block.getType()) || "FOOTER".equalsIgnoreCase(block.getType())) {
                        continue;
                    }

                    String type = block.getType() != null ? block.getType() : "";

                    if ("PARAGRAPH".equalsIgnoreCase(type) || "LIST_ITEM".equalsIgnoreCase(type)
                            || "CODE_BLOCK".equalsIgnoreCase(type) || "EMBEDDED_OBJECT".equalsIgnoreCase(type)) {
                        createParagraph(document, block, jobId);
                    } else if ("TABLE".equalsIgnoreCase(type)) {
                        createTable(document, block, jobId);
                    }
                }
            }

            // 3. Splice Headers/Footers
            applyHeadersAndFooters(document, metadata.getContentBlocks(), jobId);

            File tempFile = File.createTempFile("generated_" + jobId, ".docx");
            try (FileOutputStream out = new FileOutputStream(tempFile)) {
                document.write(out);
            }
            log.info("Finished generating document to {}", tempFile.getAbsolutePath());
            return tempFile;
        }
    }


    private void applyPageLayout(XWPFDocument document, DocumentMetadataResponse.DocumentStats stats) {
        if (stats == null || stats.getPageLayout() == null)
            return;
        DocumentMetadataResponse.PageLayoutData layout = stats.getPageLayout();

        CTSectPr sectPr = document.getDocument().getBody().isSetSectPr()
                ? document.getDocument().getBody().getSectPr()
                : document.getDocument().getBody().addNewSectPr();

        CTPageSz pageSize = sectPr.isSetPgSz() ? sectPr.getPgSz() : sectPr.addNewPgSz();
        if (layout.getPageWidth() != null)
            pageSize.setW(layout.getPageWidth().longValue());
        if (layout.getPageHeight() != null)
            pageSize.setH(layout.getPageHeight().longValue());
        if ("LANDSCAPE".equalsIgnoreCase(layout.getOrientation())) {
            pageSize.setOrient(STPageOrientation.LANDSCAPE);
        }

        CTPageMar pageMar = sectPr.isSetPgMar() ? sectPr.getPgMar() : sectPr.addNewPgMar();
        if (layout.getMarginTop() != null)
            pageMar.setTop(BigInteger.valueOf(layout.getMarginTop().longValue()));
        if (layout.getMarginBottom() != null)
            pageMar.setBottom(BigInteger.valueOf(layout.getMarginBottom().longValue()));
        if (layout.getMarginLeft() != null)
            pageMar.setLeft(BigInteger.valueOf(layout.getMarginLeft().longValue()));
        if (layout.getMarginRight() != null)
            pageMar.setRight(BigInteger.valueOf(layout.getMarginRight().longValue()));
    }

    private void applyStyles(XWPFDocument document, Map<String, DocumentMetadataResponse.StyleData> stylesData) {
        if (stylesData == null || stylesData.isEmpty())
            return;
        try {
            XWPFStyles styles = document.createStyles();

            for (Map.Entry<String, DocumentMetadataResponse.StyleData> entry : stylesData.entrySet()) {
                String styleId = entry.getKey();
                DocumentMetadataResponse.StyleData sData = entry.getValue();

                CTStyle ctStyle = CTStyle.Factory.newInstance();
                ctStyle.setStyleId(styleId);

                // Type
                if ("paragraph".equalsIgnoreCase(sData.getType())) {
                    ctStyle.setType(STStyleType.PARAGRAPH);
                } else if ("character".equalsIgnoreCase(sData.getType())) {
                    ctStyle.setType(STStyleType.CHARACTER);
                }

                // Name
                if (sData.getName() != null) {
                    CTString name = ctStyle.addNewName();
                    name.setVal(sData.getName());
                }

                // BasedOn
                if (sData.getBasedOn() != null) {
                    CTString basedOn = ctStyle.addNewBasedOn();
                    basedOn.setVal(sData.getBasedOn());
                }

                // Paragraph Properties
                CTPPrGeneral ppr = ctStyle.addNewPPr();
                if (sData.getSpacingBefore() != null || sData.getSpacingAfter() != null
                        || sData.getLineSpacing() != null) {
                    CTSpacing spacing = ppr.addNewSpacing();
                    if (sData.getSpacingBefore() != null)
                        spacing.setBefore(BigInteger.valueOf(sData.getSpacingBefore()));
                    if (sData.getSpacingAfter() != null)
                        spacing.setAfter(BigInteger.valueOf(sData.getSpacingAfter()));
                    if (sData.getLineSpacing() != null)
                        spacing.setLine(BigInteger.valueOf((long) (sData.getLineSpacing() * 240))); // Approx line rules
                }

                if (sData.getIndentFirstLine() != null || sData.getIndentLeft() != null) {
                    CTInd ind = ppr.addNewInd();
                    if (sData.getIndentFirstLine() != null)
                        ind.setFirstLine(BigInteger.valueOf(sData.getIndentFirstLine()));
                    if (sData.getIndentLeft() != null)
                        ind.setLeft(BigInteger.valueOf(sData.getIndentLeft()));
                }

                // Run Properties (Font)
                CTRPr rpr = ctStyle.addNewRPr();
                if (sData.getFontFamily() != null) {
                    CTFonts fonts = rpr.addNewRFonts();
                    fonts.setAscii(sData.getFontFamily());
                    fonts.setHAnsi(sData.getFontFamily());
                    fonts.setCs(sData.getFontFamily());
                }
                if (sData.getFontSize() != null) {
                    CTHpsMeasure sz = rpr.addNewSz();
                    sz.setVal(BigInteger.valueOf((long) (sData.getFontSize() * 2))); // Half points
                }
                if (sData.getColor() != null) {
                    CTColor color = rpr.addNewColor();
                    color.setVal(sData.getColor().replace("#", ""));
                }
                if (Boolean.TRUE.equals(sData.getIsBold()))
                    rpr.addNewB();
                if (Boolean.TRUE.equals(sData.getIsItalic()))
                    rpr.addNewI();

                XWPFStyle style = new XWPFStyle(ctStyle);
                styles.addStyle(style);
            }
        } catch (Exception e) {
            log.warn("Failed to apply document styles: {}", e.getMessage());
        }
    }

    private void applyNumbering(XWPFDocument document,
            Map<String, DocumentMetadataResponse.NumberingData> numberingData) {
        if (numberingData == null || numberingData.isEmpty())
            return;
        try {
            XWPFNumbering numbering = document.createNumbering();

            for (Map.Entry<String, DocumentMetadataResponse.NumberingData> entry : numberingData.entrySet()) {
                String numId = entry.getKey();
                DocumentMetadataResponse.NumberingData nData = entry.getValue();

                CTAbstractNum abstractNum = CTAbstractNum.Factory.newInstance();
                abstractNum.setAbstractNumId(BigInteger.valueOf(Long.parseLong(nData.getAbstractNumId())));

                if (nData.getLevels() != null) {
                    for (Map.Entry<String, DocumentMetadataResponse.ListLevelData> lvlEntry : nData.getLevels()
                            .entrySet()) {
                        int levelId = Integer.parseInt(lvlEntry.getKey());
                        DocumentMetadataResponse.ListLevelData lData = lvlEntry.getValue();

                        CTLvl lvl = abstractNum.addNewLvl();
                        lvl.setIlvl(BigInteger.valueOf(levelId));

                        if (lData.getNumFormat() != null) {
                            CTNumFmt numFmt = lvl.addNewNumFmt();
                            numFmt.setVal(STNumberFormat.Enum.forString(lData.getNumFormat().toLowerCase()));
                        }

                        if (lData.getLevelText() != null) {
                            lvl.addNewLvlText().setVal(lData.getLevelText());
                        }

                        if (lData.getAlignment() != null) {
                            lvl.addNewLvlJc().setVal(STJc.Enum.forString(lData.getAlignment().toLowerCase()));
                        }

                        CTPPrGeneral ppr = lvl.addNewPPr();
                        if (lData.getIndentLeft() != null || lData.getIndentHanging() != null) {
                            CTInd ind = ppr.addNewInd();
                            if (lData.getIndentLeft() != null)
                                ind.setLeft(BigInteger.valueOf(lData.getIndentLeft()));
                            if (lData.getIndentHanging() != null)
                                ind.setHanging(BigInteger.valueOf(lData.getIndentHanging()));
                        }

                        if (lData.getFontFamily() != null) {
                            CTRPr rpr = lvl.addNewRPr();
                            CTFonts fonts = rpr.addNewRFonts();
                            fonts.setAscii(lData.getFontFamily());
                            fonts.setHAnsi(lData.getFontFamily());
                            fonts.setCs(lData.getFontFamily());
                        }
                    }
                }

                XWPFAbstractNum xwpfAbstractNum = new XWPFAbstractNum(abstractNum);
                BigInteger abstractId = numbering.addAbstractNum(xwpfAbstractNum);
                numbering.addNum(abstractId, BigInteger.valueOf(Long.parseLong(numId)));
            }
        } catch (Exception e) {
            log.warn("Failed to apply document numbering: {}", e.getMessage());
        }
    }

    private void createParagraph(XWPFDocument document, DocumentMetadataResponse.DocumentBlock block, String jobId) {
        XWPFParagraph p = document.createParagraph();

        // 1. Отрисовка встроенных объектов (OLE)
        if ("EMBEDDED_OBJECT".equalsIgnoreCase(block.getType())) {
            XWPFRun r = p.createRun();
            r.setBold(true);
            r.setColor("FF0000"); // Красный цвет для заметности
            r.setText("[Встроенный файл: " + block.getEmbeddedObjectName() + "]");
            return; // Больше ничего не делаем
        }

        // 2. Обработка блоков кода
        if ("CODE_BLOCK".equalsIgnoreCase(block.getType())) {
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd shd = p.getCTP().getPPr() != null && p.getCTP().getPPr().isSetShd()
                    ? p.getCTP().getPPr().getShd() : (p.getCTP().getPPr() != null ? p.getCTP().getPPr().addNewShd() : p.getCTP().addNewPPr().addNewShd());
            shd.setFill("F4F4F4");

            String codeText = block.getText() != null ? block.getText() : "";
            codeText = codeText.replaceAll("^```[a-zA-Z]*\n?", "").replaceAll("```$", "").trim();

            String[] lines = codeText.split("\n");
            for (int i = 0; i < lines.length; i++) {
                XWPFRun r = p.createRun();
                r.setText(lines[i]);
                r.setFontFamily("Consolas");
                if (i < lines.length - 1) r.addBreak();
            }
            return;
        }

        // 3. Стили и выравнивание
        if (block.getStyleName() != null) p.setStyle(block.getStyleName());
        if (block.getAlignment() != null) {
            try { p.setAlignment(ParagraphAlignment.valueOf(block.getAlignment())); } catch (Exception ignored) {}
        }

        // 4. Отступы
        if (block.getIndentFirstLine() != null) p.setIndentationFirstLine(block.getIndentFirstLine());
        if (block.getIndentLeft() != null) p.setIndentationLeft(block.getIndentLeft());
        if (block.getSpacingBefore() != null) p.setSpacingBefore(block.getSpacingBefore());
        if (block.getSpacingAfter() != null) p.setSpacingAfter(block.getSpacingAfter());

        // 5. ИСПРАВЛЕНО: Правильная нумерация списков (NumID)
        if ("LIST_ITEM".equalsIgnoreCase(block.getType()) || block.getListLevel() != null) {
            BigInteger numId = block.getListNumId() != null ? new BigInteger(block.getListNumId()) : BigInteger.valueOf(1);
            p.setNumID(numId);
            p.getCTP().getPPr().getNumPr().addNewIlvl().setVal(BigInteger.valueOf(Long.parseLong(block.getListLevel() != null ? block.getListLevel() : "0")));
        }

        // 6. Наполнение контентом
        if (block.getRuns() != null && !block.getRuns().isEmpty()) {
            for (DocumentMetadataResponse.RunData runData : block.getRuns()) {
                createRun(p, runData, jobId);
            }
        } else if (block.getImageName() != null) {
            injectImage(p, block.getImageName(), block.getImageContentType(), jobId);
        } else if (block.getText() != null && !block.getText().isEmpty()) {
            p.createRun().setText(block.getText());
        }
    }

    private void createRun(XWPFParagraph p, DocumentMetadataResponse.RunData runData, String jobId) {
        XWPFRun r;
        if (Boolean.TRUE.equals(runData.getIsInternalLink()) && runData.getHyperlink() != null) {
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTHyperlink cthlink = p.getCTP().addNewHyperlink();
            cthlink.setAnchor(runData.getHyperlink());
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR ctr = cthlink.addNewR();
            r = new XWPFHyperlinkRun(cthlink, ctr, p);
            r.setColor("0000FF");
            r.setUnderline(UnderlinePatterns.SINGLE);
        } else if (runData.getHyperlink() != null && !runData.getHyperlink().isEmpty()) {
            // Нативный метод POI для ссылок (не ломает .rels)
            r = p.createHyperlinkRun(runData.getHyperlink());
            r.setColor("0000FF");
            r.setUnderline(UnderlinePatterns.SINGLE);
        } else {
            r = p.createRun();
        }

        if (runData.getText() != null)
            r.setText(runData.getText());
        if (runData.getFontFamily() != null)
            r.setFontFamily(runData.getFontFamily());
        if (runData.getFontSize() != null)
            r.setFontSize(runData.getFontSize());
        if (runData.getColor() != null)
            r.setColor(runData.getColor());
        if (Boolean.TRUE.equals(runData.getIsBold()))
            r.setBold(true);
        if (Boolean.TRUE.equals(runData.getIsItalic()))
            r.setItalic(true);

        if (runData.getTextHighlightColor() != null) {
            try {
                r.setTextHighlightColor(runData.getTextHighlightColor());
            } catch (Exception e) {
            }
        }
        if (Boolean.TRUE.equals(runData.getIsStrikeThrough()))
            r.setStrikeThrough(true);
        if (Boolean.TRUE.equals(runData.getIsSubscript()))
            r.setSubscript(VerticalAlign.SUBSCRIPT);
        if (Boolean.TRUE.equals(runData.getIsSuperscript()))
            r.setSubscript(VerticalAlign.SUPERSCRIPT);
    }

    private void injectImage(XWPFParagraph p, String imageName, String contentType, String jobId) {
        try {
            String shard = jobId.replace("-", "").substring(0, 4);
            Path imagePath = Paths.get(uploadDir, "images", shard.substring(0, 2), shard.substring(2, 4), jobId, imageName);

            if (Files.exists(imagePath)) {
                XWPFRun r = p.createRun();
                int pictureType = getPictureType(contentType);

                try (FileInputStream is = new FileInputStream(imagePath.toFile())) {
                    int widthEmu = Units.toEMU(300); // Фоллбэк размеры
                    int heightEmu = Units.toEMU(200);
                    try {
                        BufferedImage bimg = ImageIO.read(imagePath.toFile());
                        if (bimg != null) {
                            widthEmu = Units.toEMU(bimg.getWidth());
                            heightEmu = Units.toEMU(bimg.getHeight());
                        }
                    } catch (Exception graphicsError) {
                        log.warn("AWT Graphics error (expected in Docker), using fallback size for image: {}", imageName);
                    }
                    r.addPicture(is, pictureType, imageName, widthEmu, heightEmu);
                }
            }
        } catch (Exception e) {
            log.error("Failed to inject image {}", imageName, e);
        }
    }

    private int getPictureType(String contentType) {
        if (contentType == null)
            return Document.PICTURE_TYPE_JPEG;
        if (contentType.toLowerCase().contains("png"))
            return Document.PICTURE_TYPE_PNG;
        if (contentType.toLowerCase().contains("gif"))
            return Document.PICTURE_TYPE_GIF;
        return Document.PICTURE_TYPE_JPEG;
    }

    private void createTable(XWPFDocument document, DocumentMetadataResponse.DocumentBlock block, String jobId) {
        if (block.getTableRows() == null || block.getTableRows().isEmpty()) return;

        XWPFTable table = document.createTable();
        boolean isFirstRow = true;

        for (DocumentMetadataResponse.TableRowData rowData : block.getTableRows()) {
            XWPFTableRow row = isFirstRow ? table.getRow(0) : table.createRow();
            isFirstRow = false;

            if (rowData.getCells() != null) {
                for (int i = 0; i < rowData.getCells().size(); i++) {
                    XWPFTableCell cell = (i < row.getTableCells().size()) ? row.getCell(i) : row.addNewTableCell();
                    DocumentMetadataResponse.TableCellData cellData = rowData.getCells().get(i);

                    // ИСПРАВЛЕНО: Безопасный цвет ячейки (без краша на "auto")
                    if (cellData.getBackgroundColor() != null && !"auto".equalsIgnoreCase(cellData.getBackgroundColor())) {
                        cell.setColor(cellData.getBackgroundColor().replace("#", ""));
                    }

                    // ИСПРАВЛЕНО: Правильное горизонтальное объединение (GridSpan вместо HMerge)
                    if (cellData.getColSpan() != null && cellData.getColSpan() > 1) {
                        if (!cell.getCTTc().isSetTcPr()) cell.getCTTc().addNewTcPr();
                        cell.getCTTc().getTcPr().addNewGridSpan().setVal(BigInteger.valueOf(cellData.getColSpan()));
                    }

                    // Вертикальное объединение
                    if (cellData.getVMerge() != null) {
                        if (!cell.getCTTc().isSetTcPr()) cell.getCTTc().addNewTcPr();
                        CTVMerge vMerge = cell.getCTTc().getTcPr().isSetVMerge() ? cell.getCTTc().getTcPr().getVMerge() : cell.getCTTc().getTcPr().addNewVMerge();
                        vMerge.setVal("restart".equalsIgnoreCase(cellData.getVMerge()) ? STMerge.RESTART : STMerge.CONTINUE);
                    }

                    // Контент ячейки
                    if (cellData.getCellContent() != null && !cellData.getCellContent().isEmpty()) {
                        for (int pIdx = cell.getParagraphs().size() - 1; pIdx >= 0; pIdx--) cell.removeParagraph(pIdx); // Очистка
                        for (DocumentMetadataResponse.DocumentBlock contentBlock : cellData.getCellContent()) {
                            XWPFParagraph p = cell.addParagraph();
                            if (contentBlock.getRuns() != null && !contentBlock.getRuns().isEmpty()) {
                                for (DocumentMetadataResponse.RunData subRun : contentBlock.getRuns()) createRun(p, subRun, jobId);
                            } else if (contentBlock.getText() != null) {
                                p.createRun().setText(contentBlock.getText());
                            }
                        }
                    } else {
                        cell.setText(cellData.getText() != null ? cellData.getText() : "");
                    }

                    if (cell.getParagraphs().isEmpty()) cell.addParagraph(); // Страховка от краша Word
                }
            }
        }
    }

    private void applyHeadersAndFooters(XWPFDocument document, List<DocumentMetadataResponse.DocumentBlock> blocks,
            String jobId) {
        if (blocks == null || blocks.isEmpty())
            return;

        XWPFHeader header = null;
        XWPFFooter footer = null;

        for (DocumentMetadataResponse.DocumentBlock block : blocks) {
            if ("HEADER".equalsIgnoreCase(block.getType())) {
                if (header == null) {
                    header = document.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT);
                }
                appendBlockToHeaderFooter(header, block, jobId);
            } else if ("FOOTER".equalsIgnoreCase(block.getType())) {
                if (footer == null) {
                    footer = document.createFooter(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT);
                }
                appendBlockToHeaderFooter(footer, block, jobId);
            }
        }
    }

    private void appendBlockToHeaderFooter(IBody body, DocumentMetadataResponse.DocumentBlock containerBlock,
            String jobId) {
        // Headers/footers usually wrap a list of actual blocks (paragraphs/tables)
        // inside their "cellContent" or similar property
        // , or they might just contain text directly, but our DTO doesn't explicitly
        // have
        // a "children" list.
        // Assuming Header/Footer blocks have 'runs' or act like a single paragraph if
        // text exists,
        // or we need to check if they have cellContent.
        // We will treat the block as a container if it has 'cellContent', else as a
        // normal paragraph.

        List<DocumentMetadataResponse.DocumentBlock> children = null; // Assuming we add children logic if needed, but
                                                                      // for now fallback to paragraph logic

        XWPFParagraph p;
        if (body instanceof XWPFHeader) {
            p = ((XWPFHeader) body).createParagraph();
        } else if (body instanceof XWPFFooter) {
            p = ((XWPFFooter) body).createParagraph();
        } else {
            p = body.insertNewParagraph(null);
        }

        if (containerBlock.getAlignment() != null) {
            try {
                p.setAlignment(ParagraphAlignment.valueOf(containerBlock.getAlignment()));
            } catch (Exception ignored) {
            }
        }

        if (containerBlock.getRuns() != null && !containerBlock.getRuns().isEmpty()) {
            for (DocumentMetadataResponse.RunData runData : containerBlock.getRuns()) {
                createRun(p, runData, jobId);
            }
        } else if (containerBlock.getImageName() != null) {
            injectImage(p, containerBlock.getImageName(), containerBlock.getImageContentType(), jobId);
        } else if (containerBlock.getText() != null && !containerBlock.getText().isEmpty()) {
            p.createRun().setText(containerBlock.getText());
        }
    }
}
