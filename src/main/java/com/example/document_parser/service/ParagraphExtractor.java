package com.example.document_parser.service;

import com.example.document_parser.dto.DocumentMetadataResponse.DocumentBlock;
import com.example.document_parser.dto.DocumentMetadataResponse.RunData;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

@Component
public class ParagraphExtractor {

    private final ImageExtractor imageExtractor;

    public ParagraphExtractor(ImageExtractor imageExtractor) {
        this.imageExtractor = imageExtractor;
    }

    public List<DocumentBlock> extract(XWPFParagraph paragraph, String jobId) {
        List<DocumentBlock> extractedBlocks = new ArrayList<>();
        List<RunData> runs = new ArrayList<>();
        StringBuilder paragraphText = new StringBuilder();

        String listPrefix = getListPrefix(paragraph);
        if (!listPrefix.isEmpty()) {
            RunData bulletRun = new RunData();
            bulletRun.setText(listPrefix);
            runs.add(bulletRun);
            paragraphText.append(listPrefix);
        }

        for (XWPFRun run : paragraph.getRuns()) {
            extractPictures(run, jobId, extractedBlocks);
            extractTextRun(run, runs, paragraphText);
        }

        String finalText = paragraphText.toString().trim();
        if (!finalText.isEmpty() || !listPrefix.isEmpty()) {
            extractedBlocks.add(buildParagraphBlock(paragraph, paragraphText.toString(), runs));
        }

        return extractedBlocks;
    }

    private void extractPictures(XWPFRun run, String jobId, List<DocumentBlock> extractedBlocks) {
        for (XWPFPicture pic : run.getEmbeddedPictures()) {
            DocumentBlock imgBlock = imageExtractor.extract(pic, jobId);
            if (imgBlock != null) extractedBlocks.add(imgBlock);
        }
    }

    private void extractTextRun(XWPFRun run, List<RunData> runs, StringBuilder paragraphText) {
        String text = run.text();
        if (text != null && !text.isEmpty()) {
            RunData fRun = new RunData();
            fRun.setText(text);
            fRun.setIsBold(run.isBold());
            fRun.setIsItalic(run.isItalic());
            fRun.setFontFamily(run.getFontFamily());
            fRun.setColor(run.getColor());

            if (run.getFontSizeAsDouble() != null) {
                fRun.setFontSize(run.getFontSizeAsDouble());
            }

            fRun.setIsUnderline(run.getUnderline() != null && run.getUnderline() != UnderlinePatterns.NONE);

            // ИСПРАВЛЕНИЕ: Заменили name() на toString()
            if (run.getTextHighlightColor() != null && !run.getTextHighlightColor().toString().equalsIgnoreCase("none")) {
                fRun.setTextHighlightColor(run.getTextHighlightColor().toString());
            }

            runs.add(fRun);
            paragraphText.append(text);
        }
    }

    // ИСПРАВЛЕНИЕ: Вынесли создание блока в отдельный метод, как просила IDE
    private DocumentBlock buildParagraphBlock(XWPFParagraph paragraph, String text, List<RunData> runs) {
        DocumentBlock block = new DocumentBlock();
        block.setType("PARAGRAPH");
        block.setText(text);

        // ИСПРАВЛЕНИЕ: Заменили name() на toString()
        block.setAlignment(paragraph.getAlignment() != null ? paragraph.getAlignment().toString() : "LEFT");

        if (paragraph.getIndentationFirstLine() > 0) {
            block.setIndentFirstLine(paragraph.getIndentationFirstLine());
        }
        if (paragraph.getSpacingAfter() > 0) {
            block.setSpacingAfter(paragraph.getSpacingAfter());
        }
        if (paragraph.getSpacingBetween() > 1.0) {
            block.setLineSpacing(paragraph.getSpacingBetween());
        }

        block.setRuns(runs);
        return block;
    }

    private String getListPrefix(XWPFParagraph paragraph) {
        if (paragraph.getNumID() == null) {
            return "";
        }
        // ИСПРАВЛЕНИЕ: Заменили ilvl на indentLevel для IDE Spellchecker
        BigInteger indentLevel = paragraph.getNumIlvl();
        int level = indentLevel != null ? indentLevel.intValue() : 0;
        return "  ".repeat(level) + "• ";
    }
}