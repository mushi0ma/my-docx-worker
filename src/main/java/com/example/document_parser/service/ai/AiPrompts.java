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

  public static String agentToolResult(String toolName, String result) {
    return """
        TOOL_RESULT [%s]:
        %s

        Based on this result, decide your next action:
        - Use another tool if needed
        - Or provide FINAL_ANSWER: <your response>
        """.formatted(toolName, result);
  }

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

  // =================================================================
  // DOCUMENT DRAFTING PROMPTS (Task 5: Few-Shot + Strict Rules)
  // =================================================================

  /**
   * Enhanced draft document prompt с подробными few-shot примерами.
   * Используется как fallback если LangChain4j AI Service недоступен.
   */
  public static String draftDocument(String userPrompt) {
    return """
        You are an expert corporate document writer and formatter.
        Your task is to generate a highly structured document based on the user's request.

        ████████████████████████████████████████████████████████████████
        █  STRICT FORMATTING RULES (VIOLATION = INVALID OUTPUT)       █
        ████████████████████████████████████████████████████████████████

        1. Return ONLY a raw JSON array of DocumentBlock objects. NO markdown (```json), NO explanation text.
        2. Colors MUST be 6-character HEX without '#': "FF0000" (red), "000000" (black), "FFFFFF" (white).
           WRONG: "red", "#FF0000", "rgb(255,0,0)"
        3. fontSize MUST be an integer: 12, 14, 16, 24. WRONG: 12.5, "12pt", null
        4. "type" MUST be exactly one of: "PARAGRAPH", "TABLE", "LIST"
        5. "alignment" MUST be exactly one of: "LEFT", "CENTER", "RIGHT", "BOTH"
        6. NO nested arrays where the schema doesn't expect them
        7. Every PARAGRAPH must have either "runs" array OR "text" string, not both
        8. For LIST blocks: set "listNumId" to "bullet" or "ordered", items go as separate runs in the "runs" array

        ████████████████████████████████████████████████████████████████
        █  EXAMPLE 1: Commercial Proposal (Коммерческое предложение)  █
        ████████████████████████████████████████████████████████████████

        [
          {
            "type": "PARAGRAPH",
            "alignment": "CENTER",
            "spacingAfter": 200,
            "runs": [
              {"text": "КОММЕРЧЕСКОЕ ПРЕДЛОЖЕНИЕ", "isBold": true, "fontSize": 22, "color": "1A237E", "fontFamily": "Arial"}
            ]
          },
          {
            "type": "PARAGRAPH",
            "alignment": "CENTER",
            "spacingAfter": 400,
            "runs": [
              {"text": "ООО «ТехноСервис» — IT-решения для вашего бизнеса", "isItalic": true, "fontSize": 14, "color": "616161"}
            ]
          },
          {
            "type": "PARAGRAPH",
            "alignment": "LEFT",
            "runs": [
              {"text": "Уважаемый партнёр!", "isBold": true, "fontSize": 13, "color": "000000"},
              {"text": " Мы рады предложить вам комплексное решение для автоматизации бизнес-процессов.", "fontSize": 12, "color": "333333"}
            ]
          },
          {
            "type": "PARAGRAPH",
            "alignment": "LEFT",
            "spacingBefore": 200,
            "runs": [
              {"text": "Наши преимущества:", "isBold": true, "fontSize": 14, "color": "1A237E", "isUnderline": true}
            ]
          },
          {
            "type": "LIST",
            "listNumId": "bullet",
            "listLevel": "0",
            "runs": [
              {"text": "Опыт работы более 10 лет"},
              {"text": "Индивидуальный подход к каждому клиенту"},
              {"text": "Техническая поддержка 24/7"},
              {"text": "Гибкая система ценообразования"}
            ]
          },
          {
            "type": "TABLE",
            "tableRows": [
              {"cells": [
                {"text": "Услуга", "backgroundColor": "1A237E"},
                {"text": "Стоимость", "backgroundColor": "1A237E"},
                {"text": "Срок", "backgroundColor": "1A237E"}
              ]},
              {"cells": [
                {"text": "Разработка сайта"},
                {"text": "от 500 000 ₸"},
                {"text": "30 дней"}
              ]},
              {"cells": [
                {"text": "Мобильное приложение"},
                {"text": "от 1 200 000 ₸"},
                {"text": "60 дней"}
              ]}
            ]
          }
        ]

        ████████████████████████████████████████████████████████████████
        █  EXAMPLE 2: Lease Agreement (Договор аренды)                █
        ████████████████████████████████████████████████████████████████

        [
          {
            "type": "PARAGRAPH",
            "alignment": "CENTER",
            "spacingAfter": 300,
            "runs": [
              {"text": "ДОГОВОР АРЕНДЫ НЕЖИЛОГО ПОМЕЩЕНИЯ", "isBold": true, "fontSize": 18, "color": "000000"}
            ]
          },
          {
            "type": "PARAGRAPH",
            "alignment": "CENTER",
            "spacingAfter": 200,
            "runs": [
              {"text": "г. Алматы", "fontSize": 12, "color": "000000"},
              {"text": "                                         «__» _________ 2025 г.", "fontSize": 12, "color": "000000"}
            ]
          },
          {
            "type": "PARAGRAPH",
            "alignment": "LEFT",
            "spacingBefore": 200,
            "runs": [
              {"text": "1. ПРЕДМЕТ ДОГОВОРА", "isBold": true, "fontSize": 14, "color": "000000", "isUnderline": true}
            ]
          },
          {
            "type": "PARAGRAPH",
            "alignment": "BOTH",
            "runs": [
              {"text": "1.1. ", "isBold": true, "fontSize": 12},
              {"text": "Арендодатель передаёт, а Арендатор принимает во временное возмездное пользование нежилое помещение общей площадью ___ кв.м., расположенное по адресу: _______________.", "fontSize": 12, "color": "000000"}
            ]
          },
          {
            "type": "LIST",
            "listNumId": "ordered",
            "listLevel": "0",
            "runs": [
              {"text": "Срок аренды составляет 12 (двенадцать) месяцев"},
              {"text": "Арендная плата составляет ________ тенге в месяц"},
              {"text": "Оплата производится до 5-го числа каждого месяца"}
            ]
          }
        ]

        ████████████████████████████████████████████████████████████████
        █  EXAMPLE 3: Technical Report                               █
        ████████████████████████████████████████████████████████████████

        [
          {
            "type": "PARAGRAPH",
            "alignment": "CENTER",
            "runs": [
              {"text": "ТЕХНИЧЕСКИЙ ОТЧЁТ", "isBold": true, "fontSize": 20, "color": "0D47A1"}
            ]
          },
          {
            "type": "PARAGRAPH",
            "alignment": "CENTER",
            "spacingAfter": 300,
            "runs": [
              {"text": "Анализ производительности серверной инфраструктуры", "fontSize": 14, "color": "424242", "isItalic": true}
            ]
          },
          {
            "type": "PARAGRAPH",
            "runs": [
              {"text": "Введение", "isBold": true, "fontSize": 16, "color": "0D47A1"}
            ]
          },
          {
            "type": "PARAGRAPH",
            "alignment": "BOTH",
            "runs": [
              {"text": "В рамках данного отчёта проведён комплексный анализ серверной инфраструктуры компании за период Q1 2025.", "fontSize": 12, "color": "000000"}
            ]
          }
        ]

        Now generate a document for the following request. Follow the rules strictly.

        User Request:
        """
        + userPrompt;
  }

  // =================================================================
  // MULTI-AGENT PROMPTS (Task 8)
  // =================================================================

  /**
   * Planning Agent: генерирует структуру документа.
   */
  public static String planDocument(String userPrompt) {
    return """
        You are a document planning expert.
        Given a user request, generate ONLY a JSON array of section headings (strings)
        that would form the perfect structure for this document.

        Return ONLY a raw JSON array of strings, nothing else.
        No markdown, no explanation. Just the array.

        Rules:
        - Generate between 3 and 10 sections depending on complexity
        - Include a title as the first element
        - End with a conclusion or closing section
        - Headings should be in the same language as the request

        Example for "Коммерческое предложение":
        ["Коммерческое предложение", "О компании", "Наши услуги", "Преимущества", "Ценовое предложение", "Условия сотрудничества", "Контакты"]

        User request: %s
        """
        .formatted(userPrompt);
  }

  /**
   * Writing Agent: пишет контент для одной секции.
   */
  public static String writeSection(String sectionTitle, String documentTopic, String previousContext) {
    return """
        You are a professional document writer.
        Write the content for ONE section of a document.

        Document topic: %s
        Section title: %s
        Previous sections context: %s

        Rules:
        - Write 2-5 paragraphs of professional, detailed content
        - Match the formality level to the document type
        - Write in the same language as the section title
        - Return ONLY the text content, no JSON, no formatting instructions

        Write the content now:
        """.formatted(documentTopic, sectionTitle,
        previousContext.isBlank() ? "(this is the first section)" : previousContext);
  }

  /**
   * Formatting Agent: превращает сырой текст в DocumentBlock JSON.
   */
  public static String formatToBlocks(String sectionTitle, String sectionContent, boolean isFirstSection) {
    return """
        You are a document formatting expert.
        Convert the following section into a JSON array of DocumentBlock objects.

        STRICT RULES:
        - Colors MUST be 6-char HEX without '#': "000000", "1A237E", "FF0000"
        - fontSize MUST be integer: 12, 14, 16, etc.
        - type: "PARAGRAPH", "TABLE", or "LIST"
        - alignment: "LEFT", "CENTER", "RIGHT", or "BOTH"
        - Return ONLY a valid JSON array

        Section title: %s
        Is document title (make it big and centered): %s

        Section content:
        %s

        Format as JSON array of DocumentBlock objects:
        """.formatted(sectionTitle, isFirstSection ? "YES" : "NO", sectionContent);
  }

  public static String agentInstruction(String context, String instruction) {
    return """
        You are an intelligent AI assistant.
        Below is the parsed context of the user's document in JSON format.

        Document Context:
        %s

        User Instruction:
        %s

        Execute the instruction based strictly on the provided document context.
        Answer in the same language as the instruction.
        """.formatted(context, instruction);
  }
}