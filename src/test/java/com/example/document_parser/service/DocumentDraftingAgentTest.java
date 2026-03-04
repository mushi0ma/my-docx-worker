package com.example.document_parser.service;

import com.example.document_parser.dto.DocumentMetadataResponse.DocumentBlock;
import com.example.document_parser.dto.DocumentMetadataResponse.RunData;
import com.example.document_parser.service.ai.DocumentDraftingAiService;
import com.example.document_parser.service.ai.MultiAgentDocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit-тест DocumentDraftingAgent.
 * Мокаем Gemini (ChatLanguageModel) и все зависимости.
 * Проверяем:
 * 1. Парсинг идеального JSON из AI
 * 2. Edge-case: markdown fences вокруг JSON
 * 3. Fallback-стратегии (multi-agent → AI service → manual)
 */
@ExtendWith(MockitoExtension.class)
class DocumentDraftingAgentTest {

        @Mock
        private ChatLanguageModel writerModel;

        @Mock
        private DynamicDocxBuilderService docxBuilder;

        @Mock
        private MultiAgentDocumentService multiAgentService;

        @Mock
        private DocumentDraftingAiService aiService;

        @Mock
        private EmbeddingStore<TextSegment> embeddingStore;

        @Mock
        private EmbeddingModel embeddingModel;

        private ObjectMapper objectMapper;
        private DocumentDraftingAgent agent;

        @TempDir
        Path tempDir;

        @BeforeEach
        void setUp() {
                objectMapper = JsonMapper.builder().build();
                agent = new DocumentDraftingAgent(
                                writerModel, docxBuilder, objectMapper,
                                multiAgentService, aiService,
                                embeddingStore, embeddingModel);

                // Мокаем RAG: embeddingModel.embed() → пустой результат (чтобы не мешал)
                Embedding fakeEmbedding = Embedding.from(new float[] { 0.1f, 0.2f, 0.3f });
                lenient().when(embeddingModel.embed(anyString()))
                                .thenReturn(Response.from(fakeEmbedding));
                lenient().when(embeddingStore.search(any()))
                                .thenReturn(new EmbeddingSearchResult<>(List.of()));
        }

        // ================================================================
        // Стратегия 1: Multi-Agent Chain работает → builder вызывается
        // ================================================================

        @Test
        void draftNewDocument_multiAgentSuccess_usesChainAndCallsBuilder() throws Exception {
                List<DocumentBlock> expectedBlocks = List.of(
                                DocumentBlock.builder().type("PARAGRAPH").text("Title").build());
                when(multiAgentService.generateWithAgentChain(anyString(), anyString())).thenReturn(expectedBlocks);

                File fakeFile = tempDir.resolve("test.docx").toFile();
                fakeFile.createNewFile();
                when(docxBuilder.buildDocumentFromBlocks(eq(expectedBlocks), anyString())).thenReturn(fakeFile);

                File result = agent.draftNewDocument("Создай отчёт", "job-1");

                assertNotNull(result);
                verify(multiAgentService).generateWithAgentChain(anyString(), anyString());
                verify(docxBuilder).buildDocumentFromBlocks(eq(expectedBlocks), eq("job-1"));
                // writerModel НЕ должен вызываться — multiAgent справился
                verify(writerModel, never()).generate(anyString());
        }

        // ================================================================
        // Стратегия 2: Multi-Agent fail → AI Service fallback
        // ================================================================

    @Test
    void draftNewDocument_multiAgentFails_fallsBackToAiService() throws Exception {
        when(multiAgentService.generateWithAgentChain(anyString(), anyString()))
                .thenThrow(new RuntimeException("Multi-agent failed"));

        List<DocumentBlock> aiBlocks = List.of(
                DocumentBlock.builder().type("PARAGRAPH").text("AI Service result").build()
        );
        when(aiService.generateDocument(anyString())).thenReturn(aiBlocks);

        File fakeFile = tempDir.resolve("test.docx").toFile();
        fakeFile.createNewFile();
        when(docxBuilder.buildDocumentFromBlocks(eq(aiBlocks), anyString())).thenReturn(fakeFile);

        File result = agent.draftNewDocument("Создай договор", "job-2");

        assertNotNull(result);
        verify(multiAgentService).generateWithAgentChain(anyString(), anyString());
        verify(aiService).generateDocument(anyString());
        verify(writerModel, never()).generate(anyString());
    }

        // ================================================================
        // Стратегия 3: Всё fails → manual fallback через writerModel
        // ================================================================

    @Test
    void draftNewDocument_allStrategiesFail_usesManualFallback() throws Exception {
        when(multiAgentService.generateWithAgentChain(anyString(), anyString()))
                .thenThrow(new RuntimeException("fail"));

        // aiService = null → скипается (проверяем null-safe в конструкторе)
        agent = new DocumentDraftingAgent(
                writerModel, docxBuilder, objectMapper,
                multiAgentService, null, // aiService = null
                embeddingStore, embeddingModel);

        // writerModel возвращает чистый JSON
        String aiJson = """
                [{"type":"PARAGRAPH","text":"Manual fallback text"}]
                """;
        when(writerModel.generate(anyString())).thenReturn(aiJson);

        File fakeFile = tempDir.resolve("test.docx").toFile();
        fakeFile.createNewFile();
        when(docxBuilder.buildDocumentFromBlocks(any(), anyString())).thenReturn(fakeFile);

        File result = agent.draftNewDocument("Создай план", "job-3");

        assertNotNull(result);
        verify(writerModel).generate(anyString());
    }

        // ================================================================
        // Edge-case: AI обернул JSON в markdown ```json ...```
        // ================================================================

    @Test
    void draftNewDocument_aiReturnsMarkdownFences_stripsAndParses() throws Exception {
        when(multiAgentService.generateWithAgentChain(anyString(), anyString()))
                .thenThrow(new RuntimeException("fail"));

        agent = new DocumentDraftingAgent(
                writerModel, docxBuilder, objectMapper,
                multiAgentService, null,
                embeddingStore, embeddingModel);

        // AI returns JSON wrapped in Markdown fences
        String wrappedJson = """
                ```json
                [{"type":"PARAGRAPH","text":"Wrapped in fences"}]
                ```""";
        when(writerModel.generate(anyString())).thenReturn(wrappedJson);

        File fakeFile = tempDir.resolve("test.docx").toFile();
        fakeFile.createNewFile();
        when(docxBuilder.buildDocumentFromBlocks(any(), anyString())).thenReturn(fakeFile);

        // Не должно бросить исключение — fences должны быть стрипнуты
        assertDoesNotThrow(() -> agent.draftNewDocument("Тест fences", "job-4"));
        verify(writerModel).generate(anyString());
    }

        // ================================================================
        // stripMarkdownFences — параметризованный тест
        // ================================================================

        @ParameterizedTest(name = "stripMarkdownFences: {0} → {1}")
        @MethodSource("fencesProvider")
        void stripMarkdownFences_variousFormats(String input, String expected) {
                // stripMarkdownFences is private — test via ReflectionTestUtils
                String result = ReflectionTestUtils.invokeMethod(agent, "stripMarkdownFences", input);
                assertEquals(expected, result);
        }

        static Stream<Arguments> fencesProvider() {
                return Stream.of(
                                Arguments.of("```json\n[{\"type\":\"P\"}]\n```", "[{\"type\":\"P\"}]"),
                                Arguments.of("```\n[{\"x\":1}]\n```", "[{\"x\":1}]"),
                                Arguments.of("[{\"clean\":true}]", "[{\"clean\":true}]"),
                                Arguments.of(null, "[]"),
                                Arguments.of("  ```json\n{}\n```  ", "{}") // with whitespace
                );
        }
}
