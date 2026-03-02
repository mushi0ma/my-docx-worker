package com.example.document_parser.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Конфигурация AI-моделей.
 *
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  ЗАДАЧА               │  МОДЕЛЬ                      │  ПРОВАЙДЕР   │
 * ├──────────────────────────────────────────────────────────────────────┤
 * │  Summary (sync)       │  llama-4-scout-17b-16e       │  Groq        │
 * │  Summary (stream)     │  llama-4-scout-17b-16e       │  Groq        │
 * │  RAG-чат (основная)   │  qwen3-next-80b-a3b:free     │  OpenRouter  │
 * │  RAG-чат (fallback)   │  openrouter/free             │  OpenRouter  │
 * │  JSON-коррекция       │  qwen3-coder 480B:free       │  OpenRouter  │
 * │  Embeddings           │  all-minilm-l6-v2 (local)   │  локально    │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * Почему нужен fallback:
 * Бесплатные модели на OpenRouter имеют лимит 50 req/day (200 с кредитами).
 * При rate limit провайдера Venice возвращается 429 — переключаемся на
 * openrouter/free который автоматически выбирает доступную модель.
 */
@Configuration
public class LangChainConfig {

    private static final Logger log = LoggerFactory.getLogger(LangChainConfig.class);

    @Value("${spring.datasource.url:jdbc:postgresql://localhost:5432/postgres}")
    private String dbUrl;

    @Value("${spring.datasource.username:postgres}")
    private String dbUser;

    @Value("${spring.datasource.password:postgres}")
    private String dbPassword;

    @Value("${openrouter.api.key:demo}")
    private String openRouterApiKey;

    @Value("${groq.api.key:demo}")
    private String groqApiKey;

    @Value("${app.ai.timeout.groq-seconds:20}")
    private int groqTimeoutSeconds;

    @Value("${app.ai.timeout.openrouter-seconds:90}")
    private int openRouterTimeoutSeconds;

    // =================================================================
    // EMBEDDING STORE (Supabase PgVector)
    // =================================================================

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        DbCoordinates db = parseJdbcUrl(dbUrl);
        log.info("Configuring PgVectorEmbeddingStore. host={}, db={}", db.host(), db.database());
        return PgVectorEmbeddingStore.builder()
                .host(db.host())
                .port(db.port())
                .database(db.database())
                .user(dbUser)
                .password(dbPassword)
                .table("doc_embeddings_local")
                .dimension(384)
                .createTable(true)
                .dropTableFirst(false)
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        return new AllMiniLmL6V2EmbeddingModel();
    }

    // =================================================================
    // GROQ — Summary (sync + stream)
    // =================================================================

    @Bean("summaryModel")
    public ChatLanguageModel summaryModel() {
        return buildChatModel(
                "https://api.groq.com/openai/v1",
                groqApiKey,
                "meta-llama/llama-4-scout-17b-16e-instruct",
                groqTimeoutSeconds
        );
    }

    @Bean("streamingSummaryModel")
    public StreamingChatLanguageModel streamingSummaryModel() {
        return buildStreamingModel(
                "https://api.groq.com/openai/v1",
                groqApiKey,
                "meta-llama/llama-4-scout-17b-16e-instruct",
                groqTimeoutSeconds
        );
    }

    // =================================================================
    // OPENROUTER — RAG чат (основная модель)
    // =================================================================

    /**
     * Основная модель для RAG-чата.
     * qwen3-next-80b-a3b: 262K контекст, tools, рассуждения.
     * При rate limit (429) RagChatService переключается на chatModelFallback.
     */
    @Bean("streamingChatModel")
    public StreamingChatLanguageModel streamingChatModel() {
        return buildStreamingModel(
                "https://openrouter.ai/api/v1",
                openRouterApiKey,
                "qwen/qwen3-next-80b-a3b-instruct:free",
                openRouterTimeoutSeconds
        );
    }

    /**
     * Fallback-модель для RAG-чата при rate limit основной.
     * openrouter/free — автоматически выбирает любую доступную бесплатную модель.
     * Гарантирует что чат работает даже при исчерпании квоты конкретной модели.
     */
    @Bean("streamingChatModelFallback")
    public StreamingChatLanguageModel streamingChatModelFallback() {
        return buildStreamingModel(
                "https://openrouter.ai/api/v1",
                openRouterApiKey,
                "openrouter/free",
                openRouterTimeoutSeconds
        );
    }

    // =================================================================
    // OPENROUTER — JSON-коррекция
    // =================================================================

    @Bean("correctorModel")
    public ChatLanguageModel correctorModel() {
        return buildChatModel(
                "https://openrouter.ai/api/v1",
                openRouterApiKey,
                "qwen/qwen3-coder:free",
                openRouterTimeoutSeconds
        );
    }

    // =================================================================
    // Builders
    // =================================================================

    private ChatLanguageModel buildChatModel(String baseUrl, String apiKey,
                                             String modelName, int timeoutSeconds) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    private StreamingChatLanguageModel buildStreamingModel(String baseUrl, String apiKey,
                                                           String modelName, int timeoutSeconds) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    private DbCoordinates parseJdbcUrl(String url) {
        try {
            String stripped = url.replace("jdbc:postgresql://", "");
            String[] parts = stripped.split("/");
            String hostPort = parts[0];
            String database = parts[1].split("\\?")[0];
            String[] hp = hostPort.split(":");
            String host = hp[0];
            int port = hp.length > 1 ? Integer.parseInt(hp[1]) : 5432;
            return new DbCoordinates(host, port, database);
        } catch (Exception e) {
            log.warn("Failed to parse JDBC URL '{}', using defaults. error={}", url, e.getMessage());
            return new DbCoordinates("localhost", 5432, "postgres");
        }
    }

    private record DbCoordinates(String host, int port, String database) {}
}