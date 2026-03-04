package com.example.document_parser.controller;

import com.example.document_parser.config.StorageConfig;
import com.example.document_parser.exception.GlobalExceptionHandler;
import com.example.document_parser.export.DocumentExporter;
import com.example.document_parser.repository.DocumentRepository;
import com.example.document_parser.service.*;
import com.example.document_parser.service.ai.DocumentAgentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Standalone MockMvc тест DocumentController.
 *
 * Не зависит от Spring Boot @WebMvcTest — работает через
 * MockMvcBuilders.standaloneSetup(),
 * совместим с Spring Boot 4.x.
 *
 * Проверяем:
 * - 400 для невалидных запросов (пустые файлы, кривой jobId, пустой
 * prompt/instruction)
 * - 405 для неправильных HTTP-методов
 */
@ExtendWith(MockitoExtension.class)
class DocumentControllerTest {

    private MockMvc mockMvc;

    @Mock
    private DocumentProducer documentProducer;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private StorageConfig storageConfig;
    @Mock
    private DocxGeneratorService docxGeneratorService;
    @Mock
    private DocumentSummaryService documentSummaryService;
    @Mock
    private MarkdownService markdownService;
    @Mock
    private RagChatService ragChatService;
    @Mock
    private DocumentDraftingAgent documentDraftingAgent;
    @Mock
    private DocumentAgentService documentAgentService;

    @BeforeEach
    void setUp() {
        when(storageConfig.getTempStoragePath()).thenReturn(Path.of("/tmp/test-docs"));

        ObjectMapper objectMapper = JsonMapper.builder().build();

        List<DocumentExporter> exporterList = List.of();

        DocumentController controller = new DocumentController(
                documentProducer, redisTemplate, documentRepository,
                storageConfig, objectMapper, exporterList,
                docxGeneratorService, documentSummaryService,
                markdownService, ragChatService,
                documentDraftingAgent, documentAgentService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ================================================================
    // POST /parse — валидация файла
    // ================================================================

    @Test
    void parse_noFile_returns400() throws Exception {
        // POST без multipart-файла → MissingServletRequestPartException → 400
        mockMvc.perform(multipart("/api/v1/documents/parse"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void parse_nonDocxFile_returns400() throws Exception {
        mockMvc.perform(multipart("/api/v1/documents/parse")
                .file("file", "hello world".getBytes())
                .param("file", "readme.txt"))
                .andExpect(status().isBadRequest());
    }

    // ================================================================
    // GET /{jobId}/status — валидация jobId
    // ================================================================

    @Test
    void getStatus_invalidJobId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/documents/NOT-A-UUID/status"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getStatus_emptyJobId_returns400() throws Exception {
        // Пустой jobId (пустая строка) → validateJobId → InvalidJobIdException → 400
        // StandaloneSetup нормализует // в пустой pathVariable
        mockMvc.perform(get("/api/v1/documents//status"))
                .andExpect(status().isBadRequest());
    }

    // ================================================================
    // POST /generate-ai — пропущен prompt
    // ================================================================

    @Test
    void generateAi_missingPrompt_returns400() throws Exception {
        // POST with JSON body but no prompt → IllegalArgumentException → 400
        mockMvc.perform(post("/api/v1/documents/generate-ai")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ================================================================
    // POST /{jobId}/agent — пустая/null инструкция
    // ================================================================

    @Test
    void agent_emptyInstruction_returns400() throws Exception {
        String validUuid = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/documents/" + validUuid + "/agent")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"instruction\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void agent_nullInstruction_returns400() throws Exception {
        String validUuid = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/documents/" + validUuid + "/agent")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ================================================================
    // Method Not Allowed (405)
    // ================================================================

    @Test
    void getStatus_wrongMethod_POST_returns405() throws Exception {
        // POST на /{jobId}/status — этот эндпоинт принимает только GET → 405
        String validUuid = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/documents/" + validUuid + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void parse_wrongMethod_GET_returns400() throws Exception {
        // GET /parse в standaloneSetup матчится как /{jobId} (parse — не UUID) → 400
        // Это корректное поведение: Spring MVC не различает "parse" как endpoint vs
        // pathVariable
        mockMvc.perform(get("/api/v1/documents/parse"))
                .andExpect(status().isBadRequest());
    }

    // ================================================================
    // POST /chat — пустой вопрос
    // ================================================================

    @Test
    void chat_emptyQuestion_returns400() throws Exception {
        String validUuid = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/documents/" + validUuid + "/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    // ================================================================
    // POST /generate-ai — AI crashes → JSON error (not broken .docx)
    // ================================================================

    @Test
    void generateAi_aiThrows_returns500Json() throws Exception {
        when(documentDraftingAgent.draftNewDocument(anyString(), anyString()))
                .thenThrow(new RuntimeException("Model inference failed"));

        mockMvc.perform(post("/api/v1/documents/generate-ai")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\":\"Создай отчёт\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("AI document generation failed"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.jobId").exists());
    }

    @Test
    void generateAi_rateLimited_returns503() throws Exception {
        when(documentDraftingAgent.draftNewDocument(anyString(), anyString()))
                .thenThrow(new RuntimeException("429 Too Many Requests"));

        mockMvc.perform(post("/api/v1/documents/generate-ai")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\":\"Создай договор\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("AI model rate limited"))
                .andExpect(jsonPath("$.status").value(503));
    }
}
