package com.example.document_parser.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;

import java.time.Duration;

@Configuration
public class LangChainConfig {

    private static final Logger log = LoggerFactory.getLogger(LangChainConfig.class);

    @Value("${spring.datasource.url:jdbc:postgresql://localhost:5432/postgres}")
    private String dbUrl;

    @Value("${spring.datasource.username:postgres}")
    private String dbUser;

    @Value("${spring.datasource.password:postgres}")
    private String dbPassword;

    @Value("${langchain4j.open-router.api-key:demo}")
    private String openRouterApiKey;

    @Value("${groq.api.key:demo}")
    private String groqApiKey;

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        try {
            String urlWithoutJdbc = dbUrl.replace("jdbc:postgresql://", "");
            String[] parts = urlWithoutJdbc.split("/");
            String hostPort = parts[0];
            String database = parts[1].split("\\?")[0];
            String[] hostPortParts = hostPort.split(":");
            String host = hostPortParts[0];
            int port = hostPortParts.length > 1 ? Integer.parseInt(hostPortParts[1]) : 5432;

            log.info("Configuring PgVectorEmbeddingStore with host: {}, port: {}, database: {}", host, port, database);

            return PgVectorEmbeddingStore.builder()
                    .host(host).port(port).database(database).user(dbUser).password(dbPassword)
                    // ИЗМЕНЕНО: Новая таблица и новая размерность (384) для локальной модели
                    .table("doc_embeddings_local")
                    .dimension(384)
                    .createTable(true)
                    .dropTableFirst(false)
                    .build();
        } catch (Exception e) {
            return PgVectorEmbeddingStore.builder()
                    .host("localhost").port(5432).database("postgres").user(dbUser).password(dbPassword)
                    .table("doc_embeddings_local").dimension(384).createTable(true).dropTableFirst(false).build();
        }
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        return new AllMiniLmL6V2EmbeddingModel();
    }

    // 1. Агент-Корректор (Тяжелая модель Qwen Coder для исправления JSON и кода)
    @Bean("coderModel")
    public ChatLanguageModel coderModel() {
        return OpenAiChatModel.builder()
                .baseUrl("https://openrouter.ai/api/v1")
                .apiKey(openRouterApiKey)
                .modelName("qwen/qwen-2.5-coder-32b-instruct")
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    // 2. Агент-Диспетчер/Аналитик (Быстрая модель Llama на Groq для Саммари)
    @Bean("routerModel")
    public ChatLanguageModel routerModel() {
        return OpenAiChatModel.builder()
                .baseUrl("https://api.groq.com/openai/v1")
                .apiKey(groqApiKey)
                .modelName("llama-3.3-70b-versatile")
                .timeout(Duration.ofSeconds(15))
                .build();
    }

    // 3. СТРИМИНГ: Агент-Диспетчер (Groq) для быстрой генерации Summary на лету
    @Bean("streamingRouterModel")
    public StreamingChatLanguageModel streamingRouterModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl("https://api.groq.com/openai/v1")
                .apiKey(groqApiKey)
                .modelName("llama-3.3-70b-versatile")
                .timeout(Duration.ofSeconds(15))
                .build();
    }

    // 4. СТРИМИНГ: Агент-Аналитик (OpenRouter) для RAG и сложных ответов в чате
    @Bean("streamingChatModel")
    public StreamingChatLanguageModel streamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl("https://openrouter.ai/api/v1")
                .apiKey(openRouterApiKey)
                // Можешь использовать Qwen или Gemini-2.0-flash
                .modelName("arcee-ai/trinity-large-preview:free")
                .timeout(Duration.ofSeconds(60))
                .build();
    }
}