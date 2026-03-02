package com.example.document_parser.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Конфигурация асинхронных thread pool'ов.
 *
 * Три пула с разными профилями:
 *
 * documentProcessingExecutor — CPU-bound задачи (Apache POI парсинг)
 *   core = CPU cores * 2, queue = 50 задач
 *
 * llmExecutor — Network I/O (LLM API вызовы, 20-90 сек каждый)
 *   core = 10, max = 30, queue = 100 — много потоков ждущих ответа API
 *
 * vectorizationExecutor — смешанный CPU+IO (embedding генерация)
 *   core = 4, max = 8, queue = 200 — батчевая обработка чанков
 *
 * Без этого класса @Async выполнялся бы в SimpleAsyncTaskExecutor
 * (создаёт новый поток на каждый вызов — утечка ресурсов при нагрузке).
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /**
     * CPU-bound: Apache POI парсинг DOCX.
     * core * 2 потоков — оптимально для задач с умеренным IO (чтение файлов).
     */
    @Bean("documentProcessingExecutor")
    public Executor documentProcessingExecutor() {
        int cores = Runtime.getRuntime().availableProcessors();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(cores * 2);
        executor.setMaxPoolSize(cores * 4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("doc-parse-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler((r, e) ->
                log.error("documentProcessingExecutor queue full — task rejected"));
        executor.initialize();
        log.info("documentProcessingExecutor initialized. core={}, max={}", cores * 2, cores * 4);
        return executor;
    }

    /**
     * Network I/O: LLM API вызовы (Groq, OpenRouter).
     * Каждый запрос блокирует поток на 20-90 секунд — нужно много потоков.
     */
    @Bean("llmExecutor")
    public Executor llmExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(30);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("llm-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120); // LLM вызов может идти долго
        executor.setRejectedExecutionHandler((r, e) ->
                log.error("llmExecutor queue full — LLM task rejected"));
        executor.initialize();
        log.info("llmExecutor initialized. core=10, max=30");
        return executor;
    }

    /**
     * Mixed CPU+IO: embedding генерация (AllMiniLM локально).
     * Локальная ONNX модель — CPU-интенсивная, но с батчами можно распараллелить.
     */
    @Bean("vectorizationExecutor")
    public Executor vectorizationExecutor() {
        int cores = Runtime.getRuntime().availableProcessors();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(2, cores / 2));
        executor.setMaxPoolSize(Math.max(4, cores));
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("vectorize-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.setRejectedExecutionHandler((r, e) ->
                log.warn("vectorizationExecutor queue full — vectorization task rejected (non-critical)"));
        executor.initialize();
        log.info("vectorizationExecutor initialized. core={}, max={}", Math.max(2, cores / 2), Math.max(4, cores));
        return executor;
    }
}