package com.example.document_parser.service;

import com.example.document_parser.dto.DocumentMetadataResponse.DocumentBlock;
import com.example.document_parser.dto.DocumentMetadataResponse.TableRowData;
import com.example.document_parser.dto.DocumentMetadataResponse.TableCellData;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TableExtractor {

    private final ParagraphExtractor paragraphExtractor;

    public TableExtractor(ParagraphExtractor paragraphExtractor) {
        this.paragraphExtractor = paragraphExtractor;
    }

    public DocumentBlock extract(XWPFTable xwpfTable, String jobId) {
        List<TableRowData> rows = new ArrayList<>();

        for (XWPFTableRow xwpfRow : xwpfTable.getRows()) {
            TableRowData row = new TableRowData();
            List<TableCellData> cells = new ArrayList<>();

            for (XWPFTableCell xwpfCell : xwpfRow.getTableCells()) {
                TableCellData cell = new TableCellData();
                List<DocumentBlock> cellBlocks = new ArrayList<>();

                for (XWPFParagraph cellParagraph : xwpfCell.getParagraphs()) {
                    try {
                        cellBlocks.addAll(paragraphExtractor.extract(cellParagraph, jobId));
                    } catch (Exception ignored) {}
                }

                cell.setCellContent(cellBlocks);

                try {
                    if (xwpfCell.getCTTc() != null && xwpfCell.getCTTc().getTcPr() != null) {
                        if (xwpfCell.getCTTc().getTcPr().getGridSpan() != null) {
                            cell.setColSpan(xwpfCell.getCTTc().getTcPr().getGridSpan().getVal().intValue());
                        }
                        if (xwpfCell.getColor() != null) {
                            cell.setBackgroundColor(xwpfCell.getColor());
                        }
                    }
                } catch (Exception ignore) {}

                cells.add(cell);
            }
            row.setCells(cells);
            rows.add(row);
        }

        DocumentBlock block = new DocumentBlock();
        block.setType("TABLE");
        block.setTableRows(rows);

        if (!rows.isEmpty()) {
            block.setRowsCount(rows.size());
            block.setColumnsCount(rows.get(0).getCells().size());
        }

        return block;
    }
}