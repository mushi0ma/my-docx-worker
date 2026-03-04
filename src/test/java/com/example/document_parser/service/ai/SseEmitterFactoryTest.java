package com.example.document_parser.service.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-тесты метода sanitizeToken из SseEmitterFactory.
 * Проверяем фильтрацию мусорных токенов от бесплатных LLM-моделей.
 */
class SseEmitterFactoryTest {

    // ================================================================
    // sanitizeToken — мусорные токены → null
    // ================================================================

    @ParameterizedTest(name = "sanitizeToken(\"{0}\") → null (garbage)")
    @NullSource
    @ValueSource(strings = {
            "", // empty
            "   ", // whitespace only
            "\n", // lone newline
            "\r\n", // lone CRLF
            "\u200B", // zero-width space
            "\uFEFF", // BOM
            "\u200B\u200C\u200D", // multiple zero-width chars
            "<think>internal reasoning</think>", // think block — full
            "\u200B \u200C", // invisible + whitespace
    })
    void sanitizeToken_garbage_returnsNull(String input) {
        assertNull(SseEmitterFactory.sanitizeToken(input),
                "Garbage token should be filtered out (return null)");
    }

    // ================================================================
    // sanitizeToken — полезные токены → проходят
    // ================================================================

    @Test
    void sanitizeToken_normalText_passesThrough() {
        assertEquals("Hello", SseEmitterFactory.sanitizeToken("Hello"));
    }

    @Test
    void sanitizeToken_textWithNewline_preserved() {
        assertEquals("Line1\nLine2", SseEmitterFactory.sanitizeToken("Line1\nLine2"));
    }

    @Test
    void sanitizeToken_textWithTab_preserved() {
        assertEquals("\tIndented", SseEmitterFactory.sanitizeToken("\tIndented"));
    }

    // ================================================================
    // sanitizeToken — partial cleanup (useful text + garbage)
    // ================================================================

    @Test
    void sanitizeToken_thinkTagWithTrailingText_keepsUsefulPart() {
        String input = "<think>some reasoning here</think>Hello World";
        assertEquals("Hello World", SseEmitterFactory.sanitizeToken(input));
    }

    @Test
    void sanitizeToken_zeroWidthMixedWithText_keepsText() {
        String input = "\u200BHello\u200C World\uFEFF";
        assertEquals("Hello World", SseEmitterFactory.sanitizeToken(input));
    }

    @Test
    void sanitizeToken_multilineThinkBlock_stripped() {
        String input = "<think>\nstep 1\nstep 2\n</think>Result";
        assertEquals("Result", SseEmitterFactory.sanitizeToken(input));
    }

    @Test
    void sanitizeToken_controlChars_stripped() {
        // 0x01 (SOH) and 0x03 (ETX) should be removed, but text preserved
        String input = "\u0001Hello\u0003";
        assertEquals("Hello", SseEmitterFactory.sanitizeToken(input));
    }

    @Test
    void sanitizeToken_cyrillicText_preserved() {
        assertEquals("Привет мир", SseEmitterFactory.sanitizeToken("Привет мир"));
    }
}
