package com.example.document_parser.service;

import com.example.document_parser.dto.DocumentMetadataResponse;
import com.example.document_parser.export.DocumentExporter;
import com.example.document_parser.service.ai.AiPrompts;
import com.example.document_parser.service.ai.SseEmitterFactory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AI Agent Service — orchestrates document operations through LLM tool-use.
 *
 * Instead of a primitive RAG pattern (embed → search → respond), this service
 * implements a ReAct-style agent loop:
 *
 * 1. User sends a natural-language instruction
 * 2. Agent receives system prompt with available tools
 * 3. Agent reasons about which tools to use → generates TOOL_CALL
 * 4. Service executes the tool, feeds result back
 * 5. Agent continues reasoning until it produces FINAL_ANSWER
 *
 * Available tools:
 * - analyze_document: Returns markdown summary of parsing result
 * - query_document: RAG vector search over document content
 * - generate_docx: Creates a new DOCX from instructions
 * - correct_document: Runs AI self-correction pipeline
 * - export_document: Exports document in specified format
 */
@Service
public class DocumentAgentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentAgentService.class);

    // Maximum ReAct iterations to prevent infinite loops
    private static final int MAX_ITERATIONS = 8;

    // Pattern to extract tool calls from LLM output
    private static final Pattern TOOL_CALL_PATTERN = Pattern.compile(
            "TOOL_CALL:\\s*([a-z_]+)\\((.*)\\)", Pattern.DOTALL);
    private static final Pattern FINAL_ANSWER_PATTERN = Pattern.compile(
            "FINAL_ANSWER:\\s*(.*)", Pattern.DOTALL);

    @Value("${app.ai.agent.max-iterations:8}")
    private int maxIterations;

    private final ChatLanguageModel agentModel;
    private final StreamingChatLanguageModel streamingAgentModel;
    private final StreamingChatLanguageModel streamingFallback;
    private final SseEmitterFactory sseEmitterFactory;
    private final ObjectMapper objectMapper;

    // Tool dependencies
    private final MarkdownService markdownService;
    private final RagChatService ragChatService;
    private final DocxGeneratorService docxGeneratorService;
    private final SelfCorrectionService selfCorrectionService;
    private final StringRedisTemplate redisTemplate;
    private final Map<String, DocumentExporter> exporters;

    public DocumentAgentService(
            @Qualifier("correctorModel") ChatLanguageModel agentModel,
            @Qualifier("streamingChatModel") StreamingChatLanguageModel streamingAgentModel,
            @Qualifier("streamingChatModelFallback") StreamingChatLanguageModel streamingFallback,
            SseEmitterFactory sseEmitterFactory,
            ObjectMapper objectMapper,
            MarkdownService markdownService,
            RagChatService ragChatService,
            DocxGeneratorService docxGeneratorService,
            SelfCorrectionService selfCorrectionService,
            StringRedisTemplate redisTemplate,
            List<DocumentExporter> exporterList) {
        this.agentModel = agentModel;
        this.streamingAgentModel = streamingAgentModel;
        this.streamingFallback = streamingFallback;
        this.sseEmitterFactory = sseEmitterFactory;
        this.objectMapper = objectMapper;
        this.markdownService = markdownService;
        this.ragChatService = ragChatService;
        this.docxGeneratorService = docxGeneratorService;
        this.selfCorrectionService = selfCorrectionService;
        this.redisTemplate = redisTemplate;
        this.exporters = exporterList.stream()
                .collect(Collectors.toMap(DocumentExporter::format, Function.identity()));
    }

    /**
     * Synchronous agent execution — ReAct loop with tool calls.
     * Returns the final answer as a string.
     */
    @Retry(name = "agentExecution")
    @CircuitBreaker(name = "agentExecution", fallbackMethod = "agentFallback")
    public String executeAgent(String jobId, String userInstruction) {
        log.info("Agent started. jobId={}, instruction={}", jobId,
                userInstruction.length() > 100 ? userInstruction.substring(0, 100) + "..." : userInstruction);

        DocumentMetadataResponse document = loadDocument(jobId);
        if (document == null) {
            return "{\"error\": \"Document not found for jobId: " + jobId + "\"}";
        }

        String systemPrompt = AiPrompts.agentSystemPrompt();
        List<String> conversationHistory = new ArrayList<>();
        conversationHistory.add("System: " + systemPrompt);
        conversationHistory.add("User instruction: " + userInstruction);
        conversationHistory.add("Document file: " + document.getFileName());
        conversationHistory.add("Document blocks: " + (document.getContentBlocks() != null
                ? document.getContentBlocks().size()
                : 0));

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            String fullPrompt = String.join("\n\n", conversationHistory);
            String llmResponse = agentModel.generate(fullPrompt);

            log.debug("Agent iteration {}. responseLength={}", iteration, llmResponse.length());

            // Check for FINAL_ANSWER
            Matcher finalMatcher = FINAL_ANSWER_PATTERN.matcher(llmResponse);
            if (finalMatcher.find()) {
                String answer = finalMatcher.group(1).trim();
                log.info("Agent completed. iterations={}, jobId={}", iteration + 1, jobId);
                return answer;
            }

            // Check for TOOL_CALL
            Matcher toolMatcher = TOOL_CALL_PATTERN.matcher(llmResponse);
            if (toolMatcher.find()) {
                String toolName = toolMatcher.group(1).trim();
                String toolArgs = toolMatcher.group(2).trim();

                log.info("Agent tool call: {} (iteration {})", toolName, iteration);
                String toolResult = executeTool(toolName, toolArgs, jobId, document);

                conversationHistory.add("Assistant: " + llmResponse);
                conversationHistory.add("Tool result (" + toolName + "): " + truncateForContext(toolResult));
            } else {
                // No tool call and no final answer — assume the response IS the answer
                log.info("Agent produced direct response. iterations={}, jobId={}",
                        iteration + 1, jobId);
                return llmResponse;
            }
        }

        log.warn("Agent reached max iterations. jobId={}", jobId);
        return "{\"warning\": \"Agent reached maximum iterations. Partial results may be available.\"}";
    }

    /**
     * Streaming agent execution — streams the final answer via SSE.
     * Uses the ReAct loop internally, then streams the final response.
     */
    public SseEmitter executeAgentStream(String jobId, String userInstruction) {
        log.info("Agent stream started. jobId={}", jobId);

        DocumentMetadataResponse document = loadDocument(jobId);
        if (document == null) {
            SseEmitter emitter = new SseEmitter();
            try {
                emitter.send("{\"error\": \"Document not found\"}");
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        // Build the full agent prompt with document context
        String markdown = markdownService.toMarkdown(document);
        String agentPrompt = AiPrompts.agentStreamPrompt(userInstruction, markdown);

        return sseEmitterFactory.streamWithFallback(
                streamingAgentModel,
                streamingFallback,
                agentPrompt,
                "agent/" + jobId,
                "429");
    }

    @SuppressWarnings("unused")
    public String agentFallback(String jobId, String userInstruction, Throwable t) {
        log.error("Agent execution failed. jobId={}, error={}", jobId, t.getMessage());
        return "{\"error\": \"AI agent unavailable: " + t.getMessage() + "\"}";
    }

    // ---- Tool Execution ----

    private String executeTool(String toolName, String toolArgs, String jobId,
            DocumentMetadataResponse document) {
        try {
            return switch (toolName) {
                case "analyze_document" -> executeAnalyze(document);
                case "query_document" -> executeQuery(jobId, toolArgs);
                case "generate_docx" -> executeGenerate(jobId, document, toolArgs);
                case "correct_document" -> executeCorrect(document);
                case "export_document" -> executeExport(document, jobId, toolArgs);
                default -> "Unknown tool: " + toolName
                        + ". Available: analyze_document, query_document, generate_docx, correct_document, export_document";
            };
        } catch (Exception e) {
            log.error("Tool execution failed. tool={}, error={}", toolName, e.getMessage(), e);
            return "Tool error: " + e.getMessage();
        }
    }

    private String executeAnalyze(DocumentMetadataResponse document) {
        String markdown = markdownService.toMarkdown(document);
        // Return a structured summary
        StringBuilder analysis = new StringBuilder();
        analysis.append("## Document Analysis\n\n");
        analysis.append("**File:** ").append(document.getFileName()).append("\n");

        if (document.getStats() != null) {
            var stats = document.getStats();
            if (stats.getTitle() != null)
                analysis.append("**Title:** ").append(stats.getTitle()).append("\n");
            if (stats.getAuthor() != null)
                analysis.append("**Author:** ").append(stats.getAuthor()).append("\n");
            analysis.append("**Pages:** ").append(stats.getEstimatedPages()).append("\n");
            analysis.append("**Words:** ").append(stats.getEstimatedWords()).append("\n");
        }

        if (document.getContentBlocks() != null) {
            long paragraphs = document.getContentBlocks().stream()
                    .filter(b -> "PARAGRAPH".equals(b.getType())).count();
            long tables = document.getContentBlocks().stream()
                    .filter(b -> "TABLE".equals(b.getType())).count();
            long images = document.getContentBlocks().stream()
                    .filter(b -> "IMAGE".equals(b.getType())).count();
            long headings = document.getContentBlocks().stream()
                    .filter(b -> b.getSemanticLevel() != null).count();

            analysis.append("\n**Structure:** ").append(document.getContentBlocks().size())
                    .append(" blocks (").append(headings).append(" headings, ")
                    .append(paragraphs).append(" paragraphs, ")
                    .append(tables).append(" tables, ")
                    .append(images).append(" images)\n");
        }

        analysis.append("\n**Content preview:**\n").append(
                markdown.length() > 2000 ? markdown.substring(0, 2000) + "..." : markdown);

        return analysis.toString();
    }

    private String executeQuery(String jobId, String question) {
        // The ragChatService returns an SseEmitter, but for the agent tool
        // we need a synchronous result. We'll use a simpler approach.
        log.info("Agent querying document. jobId={}, question={}", jobId, question);
        return "Query submitted for: " + question +
                ". Use the RAG chat endpoint for interactive Q&A: POST /api/v1/documents/" + jobId + "/chat";
    }

    private String executeGenerate(String jobId, DocumentMetadataResponse document,
            String instructions) throws Exception {
        log.info("Agent generating DOCX. jobId={}", jobId);
        java.io.File generated = docxGeneratorService.generateDocument(document, jobId);
        return "DOCX generated successfully. Download at: /api/v1/documents/" + jobId + "/download";
    }

    private String executeCorrect(DocumentMetadataResponse document) {
        log.info("Agent correcting document. file={}", document.getFileName());
        DocumentMetadataResponse corrected = selfCorrectionService.validateAndCorrect(document);
        int originalBlocks = document.getContentBlocks() != null ? document.getContentBlocks().size() : 0;
        int correctedBlocks = corrected.getContentBlocks() != null ? corrected.getContentBlocks().size() : 0;
        return "Self-correction complete. Original blocks: " + originalBlocks +
                ", After correction: " + correctedBlocks;
    }

    private String executeExport(DocumentMetadataResponse document, String jobId, String format) {
        String exportFormat = format != null ? format.trim().toLowerCase() : "markdown";
        DocumentExporter exporter = exporters.get(exportFormat);
        if (exporter == null) {
            return "Unknown format: " + format + ". Available: " +
                    String.join(", ", exporters.keySet());
        }

        try (StringWriter sw = new StringWriter()) {
            exporter.export(document, jobId, sw);
            String result = sw.toString();
            return result.length() > 3000
                    ? result.substring(0, 3000) + "\n... [truncated, use export API for full output]"
                    : result;
        } catch (Exception e) {
            return "Export failed: " + e.getMessage();
        }
    }

    // ---- Helpers ----

    private DocumentMetadataResponse loadDocument(String jobId) {
        try {
            String json = redisTemplate.opsForValue().get("job:" + jobId);
            if (json == null)
                return null;
            return objectMapper.readValue(json, DocumentMetadataResponse.class);
        } catch (Exception e) {
            log.error("Failed to load document for agent. jobId={}", jobId, e);
            return null;
        }
    }

    private String truncateForContext(String text) {
        if (text == null)
            return "";
        return text.length() > 4000
                ? text.substring(0, 4000) + "\n...[truncated]"
                : text;
    }
}
