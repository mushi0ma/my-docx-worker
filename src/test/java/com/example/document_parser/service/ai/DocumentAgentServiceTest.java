package com.example.document_parser.service.ai;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit-тест DocumentAgentService.
 *
 * Мокаем Redis и AI. Два ключевых кейса:
 * 1. Документ найден в Redis → AI вызывается с контекстом документа
 * 2. Документ НЕ найден → AI вызывается с fallback-сообщением об ошибке
 */
@ExtendWith(MockitoExtension.class)
class DocumentAgentServiceTest {

    @Mock
    private ChatLanguageModel chatModel;

    @Mock
    private StreamingChatLanguageModel streamingChatModel;

    @Mock
    private SseEmitterFactory sseEmitterFactory;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private DocumentAgentService service;

    /** Содержимое файла mock-redis-doc.json из test resources */
    private String mockRedisContent;

    @BeforeEach
    void setUp() throws IOException {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new DocumentAgentService(chatModel, streamingChatModel, sseEmitterFactory, redisTemplate);

        // Загружаем мок из test resources
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("mock-redis-doc.json")) {
            assertNotNull(is, "mock-redis-doc.json должен существовать в test/resources");
            mockRedisContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ================================================================
    // Кейс 1: Документ есть в Redis → AI вызывается с контекстом
    // ================================================================

    @Test
    void executeAgent_documentExists_callsAiWithContext() {
        String jobId = "test-job-123";
        String instruction = "Проанализируй структуру документа";

        when(valueOperations.get("job:" + jobId)).thenReturn(mockRedisContent);
        when(chatModel.generate(anyString())).thenReturn("Документ содержит 1 параграф.");

        String result = service.executeAgent(jobId, instruction);

        // AI должен быть вызван
        assertNotNull(result);
        assertEquals("Документ содержит 1 параграф.", result);

        // Проверяем, что промпт содержит контекст документа из Redis
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatModel).generate(promptCaptor.capture());
        String prompt = promptCaptor.getValue();

        assertTrue(prompt.contains(mockRedisContent),
                "Промпт должен содержать контекст документа из Redis");
        assertTrue(prompt.contains(instruction),
                "Промпт должен содержать инструкцию пользователя");
    }

    // ================================================================
    // Кейс 2: Документа нет в Redis → AI получает fallback-сообщение
    // ================================================================

    @Test
    void executeAgent_documentNotFound_aiReceivesFallbackMessage() {
        String jobId = "non-existent-job";
        String instruction = "Что в документе?";

        when(valueOperations.get("job:" + jobId)).thenReturn(null);
        when(chatModel.generate(anyString())).thenReturn("Контекст не найден.");

        String result = service.executeAgent(jobId, instruction);

        assertNotNull(result);

        // Проверяем что AI получил fallback-сообщение вместо контекста
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatModel).generate(promptCaptor.capture());
        String prompt = promptCaptor.getValue();

        assertTrue(prompt.contains("не найден"),
                "При отсутствии документа в Redis промпт должен содержать сообщение 'не найден'");
    }

    // ================================================================
    // Кейс 3: Длинный документ → контекст обрезается
    // ================================================================

    @Test
    void executeAgent_longDocument_truncatesContext() {
        String jobId = "big-doc-job";
        String instruction = "Резюмируй";

        // Создаём строку длиннее 25000 символов
        String longContent = "A".repeat(30000);
        when(valueOperations.get("job:" + jobId)).thenReturn(longContent);
        when(chatModel.generate(anyString())).thenReturn("Резюме.");

        service.executeAgent(jobId, instruction);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatModel).generate(promptCaptor.capture());
        String prompt = promptCaptor.getValue();

        // Промпт НЕ должен содержать все 30000 символов
        assertTrue(prompt.contains("[ТЕКСТ ОБРЕЗАН]"),
                "Длинный контекст должен быть обрезан с маркером [ТЕКСТ ОБРЕЗАН]");
        assertFalse(prompt.contains("A".repeat(30000)),
                "Полные 30000 символов не должны попасть в промпт");
    }
}
