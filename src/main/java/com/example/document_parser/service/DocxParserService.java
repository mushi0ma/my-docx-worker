package com.example.document_parser.service;

import com.example.document_parser.dto.DocumentMetadataResponse;
import com.example.document_parser.dto.DocumentMetadataResponse.*;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;

@Service
public class DocxParserService {

    private static final Logger log = LoggerFactory.getLogger(DocxParserService.class);

    private final ParagraphExtractor paragraphExtractor;
    private final TableExtractor tableExtractor;

    public DocxParserService(ParagraphExtractor paragraphExtractor, TableExtractor tableExtractor) {
        this.paragraphExtractor = paragraphExtractor;
        this.tableExtractor = tableExtractor;
    }

    public DocumentMetadataResponse parseDocument(File file, String originalName, String jobId) throws Exception {
        log.info("⏳ Начинаем парсинг документа: {} (Job: {})", originalName, jobId);
        DocumentMetadataResponse response = new DocumentMetadataResponse();
        response.setFileName(originalName);

        List<DocumentBlock> blocks = new ArrayList<>();

        try (InputStream is = new FileInputStream(file);
                XWPFDocument document = new XWPFDocument(is)) {

            response.setStats(extractStats(document));
            response.setDocumentStyles(extractStyles(document));

            for (XWPFHeader header : document.getHeaderList()) {
                parseElementsSafely(header.getBodyElements(), blocks, jobId, "Header");
            }

            parseElementsSafely(document.getBodyElements(), blocks, jobId, "Body");

            for (XWPFFootnote footnote : document.getFootnotes()) {
                parseElementsSafely(footnote.getBodyElements(), blocks, jobId, "Footnote");
            }

            for (XWPFFooter footer : document.getFooterList()) {
                parseElementsSafely(footer.getBodyElements(), blocks, jobId, "Footer");
            }

        } catch (Exception e) {
            log.error("❌ Фатальная ошибка при чтении файла {}: {}", originalName, e.getMessage(), e);
            throw e;
        }

        response.setContentBlocks(blocks);
        log.info("✅ Парсинг завершен. Извлечено {} блоков.", blocks.size());
        return response;
    }

    private void parseElementsSafely(List<IBodyElement> elements, List<DocumentBlock> blocks,
            String jobId, String source) {
        for (IBodyElement element : elements) {
            try {
                if (element instanceof XWPFParagraph paragraph) {
                    blocks.addAll(paragraphExtractor.extract(paragraph, jobId));
                } else if (element instanceof XWPFTable table) {
                    blocks.add(tableExtractor.extract(table, jobId));
                }
            } catch (Exception e) {
                log.warn("⚠️ [{}] Пропущен сломанный элемент в {}: {}", jobId, source, e.getMessage());
            }
        }
    }

    private DocumentStats extractStats(XWPFDocument document) {
        DocumentStats stats = new DocumentStats();
        try {
            POIXMLProperties properties = document.getProperties();
            POIXMLProperties.CoreProperties coreProps = properties.getCoreProperties();
            POIXMLProperties.ExtendedProperties extProps = properties.getExtendedProperties();

            stats.setAuthor(coreProps.getCreator());
            stats.setTitle(coreProps.getTitle());
            stats.setCreationDate(coreProps.getCreated() != null ? coreProps.getCreated().toString() : null);
            stats.setEstimatedPages(extProps.getUnderlyingProperties().getPages());
            stats.setEstimatedWords(extProps.getUnderlyingProperties().getWords());
            stats.setCharactersWithSpaces(extProps.getUnderlyingProperties().getCharactersWithSpaces());
            stats.setPageLayout(extractPageLayout(document));
        } catch (Exception e) {
            log.warn("Не удалось извлечь статистику: {}", e.getMessage());
        }
        return stats;
    }

    /**
     * Извлекает стили документа через CTStyles XML-бины.
     *
     * POI 5.3.0 не имеет XWPFStyles.getStyleList().
     * Используем XWPFDocument.getStyle() → CTStyles.getStyleArray().
     */
    private Map<String, StyleData> extractStyles(XWPFDocument document) {
        Map<String, StyleData> styleMap = new HashMap<>();
        try {
            XWPFStyles xwpfStyles = document.getStyles();
            if (xwpfStyles == null)
                return styleMap;

            // Iterate known heading/paragraph style IDs
            // POI 5.x approach: query individual styles by ID
            String[] commonStyleIds = {
                    "Normal", "Heading1", "Heading2", "Heading3", "Heading4",
                    "Heading5", "Heading6", "Title", "Subtitle",
                    "ListParagraph", "BodyText", "Quote", "IntenseQuote",
                    "NoSpacing", "FootnoteText", "Header", "Footer"
            };

            for (String styleId : commonStyleIds) {
                try {
                    XWPFStyle style = xwpfStyles.getStyle(styleId);
                    if (style != null) {
                        StyleData ds = buildStyleDataFromXwpf(style);
                        styleMap.put(styleId, ds);
                    }
                } catch (Exception e) {
                    log.debug("Пропущен стиль {}: {}", styleId, e.getMessage());
                }
            }

            // Also try to extract all styles via the CTStyles XML bean
            try {
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyles ctStyles = document.getStyle();
                if (ctStyles != null) {
                    for (CTStyle ctStyle : ctStyles.getStyleArray()) {
                        String styleId = ctStyle.getStyleId();
                        if (styleId != null && !styleMap.containsKey(styleId)) {
                            StyleData ds = buildStyleData(ctStyle);
                            styleMap.put(styleId, ds);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("CTStyles extraction fallback failed: {}", e.getMessage());
            }

        } catch (Exception e) {
            log.warn("Ошибка извлечения стилей: {}", e.getMessage());
        }
        return styleMap;
    }

    private StyleData buildStyleDataFromXwpf(XWPFStyle style) {
        StyleData ds = new StyleData();
        ds.setName(style.getName());
        ds.setBasedOn(style.getBasisStyleID());
        ds.setType(style.getType() != null ? style.getType().toString() : null);

        CTStyle ctStyle = style.getCTStyle();
        if (ctStyle != null) {
            populateStyleFromCt(ds, ctStyle);
        }
        return ds;
    }

    private StyleData buildStyleData(CTStyle ctStyle) {
        StyleData ds = new StyleData();

        if (ctStyle.isSetName()) {
            ds.setName(ctStyle.getName().getVal());
        }
        if (ctStyle.isSetBasedOn()) {
            ds.setBasedOn(ctStyle.getBasedOn().getVal());
        }
        if (ctStyle.getType() != null) {
            ds.setType(ctStyle.getType().toString());
        }

        populateStyleFromCt(ds, ctStyle);
        return ds;
    }

    private void populateStyleFromCt(StyleData ds, CTStyle ctStyle) {
        // Run properties (font, size, color, bold, italic)
        if (ctStyle.isSetRPr()) {
            var rpr = ctStyle.getRPr();

            if (rpr.sizeOfRFontsArray() > 0) {
                var rFonts = rpr.getRFontsArray(0);
                if (rFonts.getAscii() != null) {
                    ds.setFontFamily(rFonts.getAscii());
                }
            }
            if (rpr.sizeOfSzArray() > 0 && rpr.getSzArray(0).getVal() != null) {
                ds.setFontSize(toDouble(rpr.getSzArray(0).getVal()) / 2.0);
            }
            if (rpr.sizeOfColorArray() > 0) {
                Object val = rpr.getColorArray(0).getVal();
                if (val != null) {
                    ds.setColor(val.toString());
                }
            }
            ds.setIsBold(rpr.sizeOfBArray() > 0);
            ds.setIsItalic(rpr.sizeOfIArray() > 0);
        }

        // Paragraph properties (spacing, indentation)
        if (ctStyle.isSetPPr()) {
            var ppr = ctStyle.getPPr();

            if (ppr.isSetSpacing()) {
                var spacing = ppr.getSpacing();
                if (spacing.getBefore() != null)
                    ds.setSpacingBefore(toInt(spacing.getBefore()));
                if (spacing.getAfter() != null)
                    ds.setSpacingAfter(toInt(spacing.getAfter()));
                if (spacing.getLine() != null) {
                    ds.setLineSpacing(toDouble(spacing.getLine()) / 240.0);
                }
            }
            if (ppr.isSetInd()) {
                var ind = ppr.getInd();
                if (ind.getFirstLine() != null)
                    ds.setIndentFirstLine(toInt(ind.getFirstLine()));
                if (ind.getLeft() != null)
                    ds.setIndentLeft(toInt(ind.getLeft()));
            }
        }
    }

    /** Safe cast: XmlBeans returns Object for numeric values */
    private static int toInt(Object val) {
        if (val instanceof Number n)
            return n.intValue();
        return Integer.parseInt(val.toString());
    }

    private static double toDouble(Object val) {
        if (val instanceof Number n)
            return n.doubleValue();
        return Double.parseDouble(val.toString());
    }

    private PageLayoutData extractPageLayout(XWPFDocument document) {
        try {
            PageLayoutData layout = new PageLayoutData();
            var sectPr = document.getDocument().getBody().getSectPr();
            if (sectPr == null)
                return null;

            if (sectPr.getPgSz() != null) {
                if (sectPr.getPgSz().getW() != null)
                    layout.setPageWidth(Double.parseDouble(sectPr.getPgSz().getW().toString()));
                if (sectPr.getPgSz().getH() != null)
                    layout.setPageHeight(Double.parseDouble(sectPr.getPgSz().getH().toString()));
                if (sectPr.getPgSz().getOrient() != null)
                    layout.setOrientation(sectPr.getPgSz().getOrient().toString());
            }

            if (sectPr.getPgMar() != null) {
                if (sectPr.getPgMar().getTop() != null)
                    layout.setMarginTop(Double.parseDouble(sectPr.getPgMar().getTop().toString()));
                if (sectPr.getPgMar().getBottom() != null)
                    layout.setMarginBottom(Double.parseDouble(sectPr.getPgMar().getBottom().toString()));
                if (sectPr.getPgMar().getLeft() != null)
                    layout.setMarginLeft(Double.parseDouble(sectPr.getPgMar().getLeft().toString()));
                if (sectPr.getPgMar().getRight() != null)
                    layout.setMarginRight(Double.parseDouble(sectPr.getPgMar().getRight().toString()));
            }
            return layout;
        } catch (Exception e) {
            log.warn("Не удалось извлечь page layout: {}", e.getMessage());
            return null;
        }
    }
}