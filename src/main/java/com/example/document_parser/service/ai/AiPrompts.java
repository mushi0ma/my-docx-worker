package com.example.document_parser.service.ai;

/**
 * Централизованное хранилище промптов.
 *
 * Вынесено отдельно чтобы:
 * - промпты легко найти и отредактировать без поиска по сервисам
 * - версионировать изменения промптов отдельно от бизнес-логики
 * - переиспользовать одни и те же промпты в разных сервисах
 */
public final class AiPrompts {

    private AiPrompts() {
    }

    // =================================================================
    // SUMMARY PROMPTS (Llama 4 Scout / Groq)
    // =================================================================

    /**
     * Синхронный JSON-анализ документа.
     * Возвращает строго структурированный JSON без markdown-обёрток.
     */
    public static String summaryJson(String documentContent) {
        return """
                Ты — IT-аналитик. Проанализируй документ и верни ответ СТРОГО в формате JSON.
                Никаких markdown-оберток (```json), никакого текста вне JSON.

                Схема ответа (заполни все поля):
                {
                  "documentType": "Конспект | СӨЖ | Лабораторная | Отчет | Договор | Другое",
                  "language": "kk | ru | en | mixed",
                  "summary": "Краткая суть в 2-3 предложениях на языке документа",
                  "technologies": ["список", "технологий", "если есть"],
                  "keyTopics": ["тема1", "тема2", "тема3"],
                  "complexity": "low | medium | high"
                }

                Документ:
                %s
                """.formatted(documentContent);
    }

    /**
     * Стриминговое Markdown-резюме документа.
     * Использует структурированные заголовки для удобного отображения.
     */
    public static String summaryMarkdownStream(String documentContent) {
        return """
                Ты — IT-аналитик. Напиши структурированное Markdown-резюме документа.
                Отвечай на том же языке, что и документ.

                Используй строго эту структуру:
                ## 📄 Тип документа
                ## 🌍 Язык
                ## 🛠 Стек технологий
                (пропусти если не применимо)
                ## 📌 Ключевые темы
                ## 📝 Краткое содержание

                Документ:
                %s
                """.formatted(documentContent);
    }

    // =================================================================
    // SELF-CORRECTION PROMPTS (Qwen3 Coder / OpenRouter)
    // =================================================================

    /**
     * Промпт для коррекции всего документа.
     * Qwen3 Coder отлично справляется с JSON-схемами.
     */
    public static String correctFullDocument(String dirtyJson) {
        return """
                You are a data cleaning expert specializing in JSON repair.

                Fix the broken JSON representation of a parsed document.
                Rules:
                1. Do NOT alter the JSON schema or field names
                2. Fix: missing text, broken table structures, encoding issues
                3. Replace garbled characters with plausible content or empty string
                4. Reply ONLY with valid JSON — no markdown, no explanation, no ```json wrapper

                Broken JSON:
                %s
                """.formatted(dirtyJson);
    }

    /**
     * Промпт для коррекции одного блока.
     * Более лёгкая задача — можно передавать маленький фрагмент.
     */
    public static String correctSingleBlock(String blockJson) {
        return """
                Fix this single broken document block JSON.
                Return ONLY valid JSON for this block, nothing else.
                Do not change field names or structure.

                Block:
                %s
                """.formatted(blockJson);
    }

    // =================================================================
    // RAG CHAT PROMPTS (Qwen3 Next 80B / OpenRouter)
    // =================================================================

    /**
     * RAG-чат с контекстом из векторной базы.
     * Qwen3 Next 80B хорошо работает с длинным контекстом (262K).
     */
    public static String ragChat(String context, String question) {
        return """
                Ты — умный AI-ассистент для работы с документами.

                Ответь на вопрос пользователя, опираясь ТОЛЬКО на предоставленный контекст.
                Если ответа в контексте нет — честно скажи об этом, не придумывай.
                Отвечай на том же языке, что и вопрос.

                КОНТЕКСТ ИЗ ДОКУМЕНТА:
                %s

                ВОПРОС:
                %s
                """.formatted(context, question);
    }

    // =================================================================
    // AGENT PROMPTS (ReAct-style tool orchestration)
    // =================================================================

    /**
     * System prompt for the AI Agent.
     * Defines persona, available tools, and output format for ReAct loop.
     */
    public static String agentSystemPrompt() {
        return """
                You are an intelligent document agent. You can analyze, modify, and export Word documents.

                AVAILABLE TOOLS:
                1. analyze_document() — Returns a detailed markdown analysis of the document (structure, statistics, content preview)
                2. query_document(question) — Searches the document using vector similarity and returns relevant passages
                3. generate_docx(instructions) — Generates a new DOCX file based on the document's metadata and your instructions
                4. correct_document() — Runs AI-powered self-correction on the document (fixes broken tables, missing text, encoding issues)
                5. export_document(format) — Exports the document. Formats: markdown, jsonl, tsv, finetune

                HOW TO USE TOOLS:
                To call a tool, output exactly: TOOL_CALL: tool_name(arguments)
                Example: TOOL_CALL: analyze_document()
                Example: TOOL_CALL: query_document(What is the main topic?)
                Example: TOOL_CALL: export_document(markdown)

                After receiving a tool result, reason about it and decide:
                - Call another tool if more information is needed
                - Provide FINAL_ANSWER: your complete response to the user

                RULES:
                - Think step by step before choosing tools
                - Use the minimum number of tool calls needed
                - Always provide a FINAL_ANSWER when done
                - Respond in the same language as the user's instruction
                - If a tool fails, try an alternative approach or explain the limitation
                """;
    }

    /**
     * Formats a tool execution result for inclusion in the agent context.
     */
    public static String agentToolResult(String toolName, String result) {
        return """
                TOOL_RESULT [%s]:
                %s

                Based on this result, decide your next action:
                - Use another tool if needed
                - Or provide FINAL_ANSWER: <your response>
                """.formatted(toolName, result);
    }

    /**
     * Streaming agent prompt — full document context with instruction.
     * Used when the agent streams its answer directly without tool calls.
     */
    public static String agentStreamPrompt(String userInstruction, String documentMarkdown) {
        return """
                You are an intelligent document agent working with a Word document.

                DOCUMENT CONTENT:
                %s

                USER INSTRUCTION:
                %s

                Analyze the document and fulfill the user's instruction.
                Be thorough, precise, and respond in the same language as the instruction.
                If the instruction asks you to modify or generate a document, describe exactly
                what changes should be made and provide structured output.
                """.formatted(
                documentMarkdown.length() > 20000
                        ? documentMarkdown.substring(0, 20000) + "\n...[document truncated]"
                        : documentMarkdown,
                userInstruction);
    }
}