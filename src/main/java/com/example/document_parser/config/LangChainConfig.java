package com.example.document_parser.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Configuration
public class LangChainConfig {

    private static final Logger log = LoggerFactory.getLogger(LangChainConfig.class);

    @Value("${spring.datasource.url:jdbc:postgresql://localhost:5432/postgres}")
    private String dbUrl;

    @Value("${spring.datasource.username:postgres}")
    private String dbUser;

    @Value("${spring.datasource.password:postgres}")
    private String dbPassword;

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        try {
            // Strip jdbc:postgresql://
            String urlWithoutJdbc = dbUrl.replace("jdbc:postgresql://", "");
            // Typical format: localhost:5432/postgres?options
            String[] parts = urlWithoutJdbc.split("/");
            String hostAndPort = parts[0];
            String database = parts.length > 1 ? parts[1].split("\\?")[0] : "postgres";

            String host = hostAndPort.contains(":") ? hostAndPort.split(":")[0] : hostAndPort;
            int port = hostAndPort.contains(":") ? Integer.parseInt(hostAndPort.split(":")[1]) : 5432;

            log.info("Configuring PgVectorEmbeddingStore with host: {}, port: {}, database: {}", host, port, database);

            return PgVectorEmbeddingStore.builder()
                    .host(host)
                    .port(port)
                    .database(database)
                    .user(dbUser)
                    .password(dbPassword)
                    .table("document_embeddings")
                    .dimension(768) // nomic-embed-text generates 768d embeddings usually
                    .createTable(true)
                    .dropTableFirst(false)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse DB URL for PgVector store, fallback to default. Error: {}", e.getMessage());
            return PgVectorEmbeddingStore.builder()
                    .host("localhost")
                    .port(5432)
                    .database("postgres")
                    .user(dbUser)
                    .password(dbPassword)
                    .table("document_embeddings")
                    .dimension(768)
                    .createTable(true)
                    .dropTableFirst(false)
                    .build();
        }
    }

    @Bean
    public EmbeddingModel embeddingModel(@Value("${langchain4j.open-router.api-key:demo}") String apiKey) {
        // Using OpenRouter for Nomic Embed Text natively mapping to OpenAi config
        return OpenAiEmbeddingModel.builder()
                .baseUrl("https://openrouter.ai/api/v1")
                .apiKey(apiKey)
                .modelName("nomic-ai/nomic-embed-text-v1.5")
                .build();
    }

    @Bean
    public dev.langchain4j.model.chat.ChatLanguageModel chatLanguageModel(
            @Value("${langchain4j.open-router.api-key:demo}") String apiKey) {
        return dev.langchain4j.model.openai.OpenAiChatModel.builder()
                .baseUrl("https://openrouter.ai/api/v1")
                .apiKey(apiKey)
                .modelName("meta-llama/llama-3.3-70b-instruct") // Groq/OpenRouter model as requested
                .build();
    }
}
