package com.example.document_parser.service;

import com.example.document_parser.dto.DocumentMetadataResponse;

/**
 * @deprecated Этот класс является мёртвым кодом.
 *
 * Вся логика TSV/JSONL экспорта перенесена в:
 * - {@link com.example.document_parser.export.TsvDocumentExporter} — TSV через /export/tsv
 * - {@link com.example.document_parser.export.JsonlDocumentExporter} — JSONL через /export/jsonl
 *
 * Эти классы регистрируются через паттерн Strategy (List<DocumentExporter> в контроллере)
 * и поддерживают стриминг без загрузки всего документа в RAM.
 *
 * Этот класс будет удалён в следующей итерации.
 * Не добавляй новую логику сюда.
 */
@Deprecated(since = "1.1.0", forRemoval = true)
public class TsvExportService {
    // Намеренно пусто — см. javadoc выше
}