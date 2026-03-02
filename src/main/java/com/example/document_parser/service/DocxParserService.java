package com.example.document_parser.service;

import com.example.document_parser.dto.DocumentMetadataResponse;
import com.example.document_parser.dto.DocumentMetadataResponse.*;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.xwpf.usermodel.*;
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

    private void parseElementsSafely(List<IBodyElement> elements, List<DocumentBlock> blocks, String jobId, String source) {
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

    private Map<String, StyleData> extractStyles(XWPFDocument document) {
        Map<String, StyleData> styleMap = new HashMap<>();
        try {
            XWPFStyles styles = document.getStyles();
            if (styles == null) return styleMap;

            java.lang.reflect.Field listField = XWPFStyles.class.getDeclaredField("listStyle");
            listField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<XWPFStyle> styleList = (List<XWPFStyle>) listField.get(styles);

            if (styleList != null) {
                for (XWPFStyle style : styleList) {
                    StyleData ds = new StyleData();
                    ds.setName(style.getName());
                    ds.setBasedOn(style.getBasisStyleID());
                    // ИСПРАВЛЕНИЕ: Заменили name() на toString()
                    ds.setType(style.getType() != null ? style.getType().toString() : null);

                    // Убрали глубокое чтение CTRPr, так как в новых версиях POI
                    // методы getSz() и getColor() скрыты или возвращают разные типы
                    styleMap.put(style.getStyleId(), ds);
                }
            }
        } catch (Exception e) {
            log.warn("Ошибка извлечения стилей: {}", e.getMessage());
        }
        return styleMap;
    }

    private PageLayoutData extractPageLayout(XWPFDocument document) {
        try {
            PageLayoutData layout = new PageLayoutData();
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr sectPr = document.getDocument().getBody().getSectPr();
            if (sectPr == null) return null;

            // ИСПРАВЛЕНИЕ: Вернули твой оригинальный метод Double.parseDouble()
            if (sectPr.getPgSz() != null) {
                if (sectPr.getPgSz().getW() != null) layout.setPageWidth(Double.parseDouble(sectPr.getPgSz().getW().toString()));
                if (sectPr.getPgSz().getH() != null) layout.setPageHeight(Double.parseDouble(sectPr.getPgSz().getH().toString()));
                if (sectPr.getPgSz().getOrient() != null) layout.setOrientation(sectPr.getPgSz().getOrient().toString());
            }

            if (sectPr.getPgMar() != null) {
                if (sectPr.getPgMar().getTop() != null) layout.setMarginTop(Double.parseDouble(sectPr.getPgMar().getTop().toString()));
                if (sectPr.getPgMar().getBottom() != null) layout.setMarginBottom(Double.parseDouble(sectPr.getPgMar().getBottom().toString()));
                if (sectPr.getPgMar().getLeft() != null) layout.setMarginLeft(Double.parseDouble(sectPr.getPgMar().getLeft().toString()));
                if (sectPr.getPgMar().getRight() != null) layout.setMarginRight(Double.parseDouble(sectPr.getPgMar().getRight().toString()));
            }
            return layout;
        } catch (Exception e) {
            return null;
        }
    }
}