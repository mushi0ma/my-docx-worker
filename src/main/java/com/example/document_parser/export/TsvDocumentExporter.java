package com.example.document_parser.export;

import com.example.document_parser.dto.DocumentMetadataResponse;
import com.example.document_parser.dto.DocumentMetadataResponse.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Writer;
import java.util.StringJoiner;

@Component
public class TsvDocumentExporter implements DocumentExporter {

    @Override public String format()        { return "tsv"; }
    @Override public String contentType()   { return "text/tab-separated-values;charset=UTF-8"; }
    @Override public String fileExtension() { return ".tsv"; }

    private static final String HEADER =
            "document_id\tblock_index\tblock_type\tstyle\talignment\ttext\tformatting_tags\tfont_family\tfont_size";

    @Override
    public void export(DocumentMetadataResponse doc, String jobId, Writer writer) throws IOException {
        writer.write(HEADER + "\n");

        if (doc.getContentBlocks() == null) return;

        int idx = 0;
        for (DocumentBlock block : doc.getContentBlocks()) {
            if (block.getRuns() == null || block.getRuns().isEmpty()) {
                writeRow(writer, jobId, idx++, block.getType(), block.getStyleName(),
                        block.getAlignment(), block.getText(), "", "", "");
            } else {
                String tags = buildTags(block.getRuns());
                String font = dominantFont(block.getRuns());
                String size = dominantSize(block.getRuns());
                writeRow(writer, jobId, idx++, block.getType(), block.getStyleName(),
                        block.getAlignment(), block.getText(), tags, font, size);
            }
        }
        writer.flush();
    }

    private void writeRow(Writer writer, String jobId, int idx, String type, String style,
                          String align, String text, String tags, String font, String size) throws IOException {
        String safeText = text != null ? text.replace("\n", " ").replace("\t", " ") : "";
        writer.write(String.format("%s\t%d\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n",
                jobId != null ? jobId : "", idx, type != null ? type : "", style != null ? style : "",
                align != null ? align : "", safeText, tags, font, size));
    }

    private String buildTags(java.util.List<RunData> runs) {
        if (runs == null || runs.isEmpty()) return "";
        StringJoiner j = new StringJoiner(",");
        boolean bold = false, italic = false, underline = false, link = false;
        for (RunData r : runs) {
            if (Boolean.TRUE.equals(r.getIsBold()))      bold = true;
            if (Boolean.TRUE.equals(r.getIsItalic()))    italic = true;
            if (Boolean.TRUE.equals(r.getIsUnderline())) underline = true;
            if (r.getHyperlink() != null)                link = true;
        }
        if (bold) j.add("bold"); if (italic) j.add("italic");
        if (underline) j.add("underline"); if (link) j.add("hyperlink");
        return j.toString();
    }

    private String dominantFont(java.util.List<RunData> runs) {
        if (runs == null) return "";
        return runs.stream().filter(r -> r.getFontFamily() != null).map(RunData::getFontFamily).findFirst().orElse("");
    }

    private String dominantSize(java.util.List<RunData> runs) {
        if (runs == null) return "";
        return runs.stream().filter(r -> r.getFontSize() != null).map(r -> String.valueOf(r.getFontSize())).findFirst().orElse("");
    }
}