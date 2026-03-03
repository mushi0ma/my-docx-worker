package com.example.document_parser.service.ai;

/**
 * Централизованное хранилище промптов.
 *
 * Вынесено отдельно чтобы:
 * - промпты легко найти и отредактировать без поиска по сервисам
 * - версионировать изменения промптов отдельно от бизнес-логики
 * - переиспользовать одни и те же промпты в разных сервисах
 *
 * МОДЕЛИ И ИХ ОСОБЕННОСТИ:
 * - summaryJson / summaryMarkdownStream: Llama 4 Scout (Groq)
 * → быстрая, но склонна к thinking-токенам и длинным вступлениям
 * - correctFullDocument / correctSingleBlock: Qwen3 Coder (OpenRouter)
 * → code-oriented, отлично фиксит JSON, но может добавлять комментарии
 * - ragChat: Qwen3 Next 80B (OpenRouter)
 * → сильная reasoning-модель, хороша для RAG, но многословна
 * - draftDocument / planDocument / writeSection / formatToBlocks: Gemini 2.0
 * Flash
 * → мультимодальная, часто оборачивает JSON в markdown fences (```json)
 * - agentSystemPrompt / agentInstruction: ChatModel (configurable)
 * → ReAct agent, нужны чёткие stop-sequences
 */
public final class AiPrompts {

  private AiPrompts() {
  }

  // =================================================================
  // SUMMARY PROMPTS (Llama 4 Scout / Groq)
  //
  // Llama 4 Scout:
  // + Быстрая inference на Groq
  // - Может генерировать thinking-токены
  // - Склонна к длинным вступлениям перед JSON
  // → Жёсткий запрет на markdown, thinking, пояснения
  // =================================================================

  public static String summaryJson(String documentContent) {
    return """
        YOU ARE A STRICT JSON-ONLY IT ANALYST.

        ╔══════════════════════════════════════════════════════════════╗
        ║  CRITICAL: OUTPUT MUST BE RAW JSON — NOTHING ELSE          ║
        ║  • No ```json wrapper   • No <think> tags                  ║
        ║  • No text before JSON  • No text after JSON               ║
        ║  • No markdown          • No explanations                  ║
        ╚══════════════════════════════════════════════════════════════╝

        REQUIRED JSON SCHEMA — fill ALL fields, omit nothing:
        {
          "documentType": "Конспект | СӨЖ | Лабораторная | Отчет | Договор | Статья | Другое",
          "language": "kk | ru | en | mixed",
          "summary": "2-3 sentence summary IN THE DOCUMENT'S LANGUAGE",
          "technologies": ["at least one item, use 'Нет' if none"],
          "keyTopics": ["тема1", "тема2", "тема3"],
          "complexity": "low | medium | high"
        }

        CORRECT OUTPUT EXAMPLE:
        {"documentType":"Лабораторная","language":"kk","summary":"DOM API арқылы интерактивті басқару панелін құру жұмысы.","technologies":["JavaScript","HTML","DOM API"],"keyTopics":["DOM манипуляциялар","Оқиға өңдеу","UI"],"complexity":"medium"}

        WRONG OUTPUT (NEVER DO THIS):
        ```json
        {"documentType": ...}
        ```

        Analyze THIS document now:
        ---
        %s
        ---
        Respond with the JSON object only.
        """
        .formatted(documentContent);
  }

  public static String summaryMarkdownStream(String documentContent) {
    return """
        You are an IT analyst. Write a structured Markdown summary of the document below.
        Respond IN THE SAME LANGUAGE as the document.

        RULES:
        1. Do NOT output <think> tags or internal reasoning.
        2. Start your response DIRECTLY with the first heading (## 📄).
        3. Use EXACTLY this structure — no extra sections, no deviations:

        ## 📄 Тип документа
        [one line: document type]

        ## 🌍 Язык
        [one line: language code and name]

        ## 🛠 Стек технологий
        - [technology 1]
        - [technology 2]
        (Write "Не применимо" if no technologies)

        ## 📌 Ключевые темы
        - [topic 1]
        - [topic 2]
        - [topic 3]

        ## 📝 Краткое содержание
        [2-4 sentences summarizing the document]

        Document to analyze:
        ---
        %s
        ---
        Begin your response with "## 📄" immediately.
        """.formatted(documentContent);
  }

  // =================================================================
  // SELF-CORRECTION PROMPTS (Qwen3 Coder / OpenRouter)
  //
  // Qwen3 Coder:
  // + Excellent at code/JSON repair
  // + Understands data structures well
  // - May add inline comments (// or /* */) in JSON output
  // - May wrap output in markdown fences
  // → Explicit ban on comments and fences, output-only instruction
  // =================================================================

  public static String correctFullDocument(String dirtyJson) {
    return """
        ROLE: You are a JSON repair specialist. You fix broken JSON data without changing the schema.

        INPUT: A broken JSON representation of a parsed DOCX document.

        YOUR TASK:
        1. Fix ALL structural JSON errors (missing quotes, trailing commas, unclosed brackets)
        2. Fix broken table structures (mismatched row lengths, null cells → empty strings)
        3. Replace garbled/corrupted characters with plausible content or ""
        4. Preserve ALL field names exactly as they are — do NOT rename or restructure
        5. Ensure every string value is properly escaped

        FORBIDDEN ACTIONS:
        ✗ Do NOT add new fields that don't exist in the input
        ✗ Do NOT wrap output in ```json or ``` fences
        ✗ Do NOT add // comments or /* */ comments
        ✗ Do NOT write any explanation, preamble, or postscript
        ✗ Do NOT use trailing commas

        OUTPUT: Valid JSON only. First character must be '{' or '['. Last character must be '}' or ']'.

        BROKEN JSON TO FIX:
        %s
        """.formatted(dirtyJson);
  }

  public static String correctSingleBlock(String blockJson) {
    return """
        Fix this single broken DocumentBlock JSON. Return ONLY valid JSON.

        RULES:
        • Do NOT change field names or add new fields
        • Do NOT wrap in markdown (no ```)
        • Do NOT add comments
        • First character of output = '{', last character = '}'
        • Fix encoding issues, missing quotes, structural errors

        EXAMPLE INPUT:
        {"type": "PARAGRAPH", text: "broken, "runs": [{"text": "hello}]}

        EXAMPLE OUTPUT:
        {"type":"PARAGRAPH","text":"broken","runs":[{"text":"hello"}]}

        YOUR INPUT TO FIX:
        %s
        """.formatted(blockJson);
  }

  // =================================================================
  // RAG CHAT PROMPTS (Qwen3 Next 80B / OpenRouter)
  //
  // Qwen3 Next 80B:
  // + Strong reasoning and comprehension
  // + Good at following complex instructions
  // - Can be verbose and over-explain
  // - May hallucinate if context is insufficient
  // → Strict grounding rules, citation requirement, brevity instruction
  // =================================================================

  public static String ragChat(String context, String question) {
    return """
        You are a precise document Q&A assistant. Answer questions ONLY based on the provided context.

        ╔═══════════════════════════════════════════════════╗
        ║  GROUNDING RULES — MANDATORY                     ║
        ╠═══════════════════════════════════════════════════╣
        ║  1. Use ONLY information from the CONTEXT below  ║
        ║  2. If the answer is NOT in the context, say:    ║
        ║     "В предоставленном контексте нет информации   ║
        ║      для ответа на этот вопрос."                  ║
        ║  3. Do NOT invent facts, dates, or numbers       ║
        ║  4. When possible, quote exact phrases from the  ║
        ║     context to support your answer               ║
        ║  5. Answer in the SAME LANGUAGE as the question  ║
        ║  6. Be concise — 2-5 sentences max unless the    ║
        ║     question explicitly asks for detail           ║
        ╚═══════════════════════════════════════════════════╝

        CONTEXT FROM DOCUMENT:
        ---
        %s
        ---

        USER QUESTION: %s

        Answer based strictly on the context above:
        """.formatted(context, question);
  }

  // =================================================================
  // AGENT PROMPTS (ReAct-style tool orchestration)
  //
  // ChatModel (configurable):
  // → Clear stop-sequences, explicit tool format, step-by-step
  // =================================================================

  public static String agentSystemPrompt() {
    return """
        You are an intelligent document agent that can analyze, modify, and export Word documents.

        ╔══════════════════════════════════════════════════════╗
        ║  AVAILABLE TOOLS                                    ║
        ╠══════════════════════════════════════════════════════╣
        ║  1. analyze_document()                              ║
        ║     → Detailed markdown analysis (structure, stats) ║
        ║  2. query_document(question)                        ║
        ║     → Vector similarity search in the document      ║
        ║  3. generate_docx(instructions)                     ║
        ║     → Generate new DOCX from metadata               ║
        ║  4. correct_document()                              ║
        ║     → AI self-correction (fixes tables, encoding)   ║
        ║  5. export_document(format)                         ║
        ║     → Export: markdown | jsonl | tsv | finetune     ║
        ╚══════════════════════════════════════════════════════╝

        HOW TO CALL A TOOL — use EXACTLY this format (no variations):
          TOOL_CALL: tool_name(arguments)

        EXAMPLES:
          TOOL_CALL: analyze_document()
          TOOL_CALL: query_document(What is the main topic of this document?)
          TOOL_CALL: export_document(markdown)

        AFTER receiving TOOL_RESULT, you MUST either:
          a) Call another tool: TOOL_CALL: ...
          b) Give final answer: FINAL_ANSWER: your complete response

        RULES:
        1. Think step-by-step BEFORE choosing a tool
        2. Use the MINIMUM number of tool calls needed
        3. ALWAYS end with FINAL_ANSWER: when done
        4. Respond in the same language as the user's instruction
        5. If a tool fails, explain the limitation — do NOT retry infinitely
        6. NEVER invent tool names that are not in the list above
        """;
  }

  public static String agentToolResult(String toolName, String result) {
    return """
        TOOL_RESULT [%s]:
        ---
        %s
        ---

        Based on this result, choose your next action:
        • If you need more information → TOOL_CALL: tool_name(args)
        • If you can answer the user  → FINAL_ANSWER: <your response>
        """.formatted(toolName, result);
  }

  public static String agentStreamPrompt(String userInstruction, String documentMarkdown) {
    return """
        You are an intelligent document agent working with a Word document.

        DOCUMENT CONTENT (may be truncated):
        ---
        %s
        ---

        USER INSTRUCTION: %s

        RULES:
        1. Analyze the document and fulfill the instruction precisely
        2. Be thorough but concise — avoid unnecessary repetition
        3. Respond in the same language as the instruction
        4. If asked to modify/generate a document, provide structured output
        5. Always reference specific parts of the document when relevant
        """.formatted(
        documentMarkdown.length() > 20000
            ? documentMarkdown.substring(0, 20000) + "\n...[документ обрезан, показаны первые 20000 символов]"
            : documentMarkdown,
        userInstruction);
  }

  // =================================================================
  // DOCUMENT DRAFTING PROMPTS (Gemini 2.0 Flash)
  //
  // Gemini 2.0 Flash:
  // + Fast, creative, good at structured output
  // - FREQUENTLY wraps JSON in ```json ... ``` fences
  // - Sometimes adds explanatory text before/after JSON
  // - May use "color": "#FF0000" instead of "FF0000"
  // → Triple emphasis on raw JSON, explicit negative examples
  // =================================================================

  /**
   * Enhanced draft document prompt с подробными few-shot примерами.
   * Используется как fallback если LangChain4j AI Service недоступен.
   */
  public static String draftDocument(String userPrompt) {
    return """
        You are an expert corporate document writer.
        Generate a formatted Word document as a JSON array of DocumentBlock objects.

        ╔══════════════════════════════════════════════════════════════════╗
        ║  CRITICAL OUTPUT RULES — VIOLATION = REJECTED OUTPUT           ║
        ╠══════════════════════════════════════════════════════════════════╣
        ║  1. Return ONLY a raw JSON array. First char: '['. Last: ']'   ║
        ║  2. ABSOLUTELY NO ```json wrapper, no ``` fences anywhere      ║
        ║  3. ABSOLUTELY NO text before or after the JSON array          ║
        ║  4. Colors = 6-char HEX WITHOUT '#': "FF0000", NOT "#FF0000"  ║
        ║  5. fontSize = integer ONLY: 12, 14, 16. NOT 12.5 or "12pt"  ║
        ║  6. type = "PARAGRAPH" | "TABLE" | "LIST" (exact strings)     ║
        ║  7. alignment = "LEFT" | "CENTER" | "RIGHT" | "BOTH"          ║
        ║  8. PARAGRAPH block = "runs" array XOR "text" string, NOT both║
        ║  9. LIST block: "listNumId" = "bullet" or "ordered"           ║
        ╚══════════════════════════════════════════════════════════════════╝

        ⛔ WRONG — NEVER DO THIS:
        ```json
        [{"type": "PARAGRAPH", ...}]
        ```

        ⛔ WRONG:
        Here is your document:
        [{"type": "PARAGRAPH", ...}]

        ✅ CORRECT — DO EXACTLY THIS:
        [{"type":"PARAGRAPH","alignment":"CENTER","runs":[{"text":"Title","isBold":true,"fontSize":22,"color":"1A237E"}]}]

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

        Now generate a document for this request. Output RAW JSON ARRAY ONLY — first character '[', last character ']'.

        User Request:
        """
        + userPrompt;
  }

  // =================================================================
  // MULTI-AGENT PROMPTS (Gemini 2.0 Flash)
  //
  // Gemini specifics:
  // - loves to add ```json fences → explicit prohibition
  // - good at creative planning → leverage for section generation
  // - may add explanatory text → strict output-only rules
  // =================================================================

  /**
   * Planning Agent: генерирует структуру документа.
   */
  public static String planDocument(String userPrompt) {
    return """
        You are a document structure planning expert.

        TASK: Generate a JSON array of section headings for the requested document.

        RULES:
        1. Return ONLY a raw JSON array of strings — no markdown, no explanation
        2. First character of output MUST be '[', last MUST be ']'
        3. Generate 3-10 sections depending on document complexity
        4. First element = document title
        5. Last element = conclusion/closing section
        6. Headings MUST be in the same language as the request
        7. Do NOT wrap in ```json ``` — raw JSON only

        ⛔ WRONG: ```json ["Title", ...]```
        ⛔ WRONG: Here are the sections: ["Title", ...]
        ✅ CORRECT: ["Title","Section 1","Section 2","Conclusion"]

        EXAMPLE — request "Коммерческое предложение":
        ["Коммерческое предложение","О компании","Наши услуги","Преимущества","Ценовое предложение","Условия сотрудничества","Контакты"]

        Generate sections for: %s
        """
        .formatted(userPrompt);
  }

  /**
   * Writing Agent: пишет контент для одной секции.
   */
  public static String writeSection(String sectionTitle, String documentTopic, String previousContext) {
    return """
        You are a professional document writer. Write content for ONE section of a document.

        CONTEXT:
        • Document topic: %s
        • Current section: %s
        • Previous sections summary: %s

        RULES:
        1. Write 2-5 paragraphs of professional, detailed content
        2. Match formality level to the document type (formal for contracts, technical for reports)
        3. Write in the same language as the section title
        4. Return ONLY the text content — no JSON, no formatting, no headings
        5. Ensure logical continuity with previous sections — do not repeat information
        6. Use specific details, not vague generalities
        7. If this is a contract/legal document, use appropriate legal phrasing

        Write the content for this section now. Output raw text only:
        """.formatted(documentTopic, sectionTitle,
        previousContext.isBlank() ? "(this is the first section — set the tone)" : previousContext);
  }

  /**
   * Formatting Agent: превращает сырой текст в DocumentBlock JSON.
   */
  public static String formatToBlocks(String sectionTitle, String sectionContent, boolean isFirstSection) {
    return """
        You are a document formatting expert. Convert text into a JSON array of DocumentBlock objects.

        ╔══════════════════════════════════════════════╗
        ║  OUTPUT: RAW JSON ARRAY ONLY                ║
        ║  • First char = '[', last char = ']'        ║
        ║  • NO ```json    • NO markdown fences       ║
        ║  • NO explanation before or after            ║
        ╚══════════════════════════════════════════════╝

        FIELD RULES:
        • "type": "PARAGRAPH" | "TABLE" | "LIST"
        • "alignment": "LEFT" | "CENTER" | "RIGHT" | "BOTH"
        • colors: 6-char HEX without '#' → "1A237E", "000000", "FF0000"
        • fontSize: integer only → 12, 14, 16, 22
        • "runs": array of {text, isBold, isItalic, isUnderline, fontSize, color, fontFamily}

        Section title: %s
        Is document title (make it large + centered): %s

        Text to format:
        ---
        %s
        ---

        Convert to JSON array now. First character must be '[':
        """.formatted(sectionTitle, isFirstSection ? "YES — use fontSize 22, alignment CENTER, isBold true" : "NO",
        sectionContent);
  }

  public static String agentInstruction(String context, String instruction) {
    return """
        You are an intelligent AI assistant analyzing a Word document.

        DOCUMENT CONTEXT (parsed JSON):
        ---
        %s
        ---

        USER INSTRUCTION: %s

        RULES:
        1. Execute the instruction based STRICTLY on the provided document context
        2. Answer in the same language as the instruction
        3. Be precise and reference specific parts of the document when relevant
        4. If the document context says "не найден", inform the user that the document \
        is not available and suggest uploading it first
        5. Do NOT invent information that is not in the context
        """.formatted(context, instruction);
  }
}
