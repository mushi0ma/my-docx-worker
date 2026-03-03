package com.example.document_parser.service.ai;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class DocumentAgentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentAgentService.class);

    private final ChatLanguageModel chatModel;
    private final StreamingChatLanguageModel streamingChatModel;
    private final SseEmitterFactory sseEmitterFactory;
    private final StringRedisTemplate redisTemplate;

    public DocumentAgentService(
            @Qualifier("chatModel") ChatLanguageModel chatModel,
            @Qualifier("streamingChatModel") StreamingChatLanguageModel streamingChatModel,
            SseEmitterFactory sseEmitterFactory,
            StringRedisTemplate redisTemplate) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.sseEmitterFactory = sseEmitterFactory;
        this.redisTemplate = redisTemplate;
    }

    public String executeAgent(String jobId, String instruction) {
        log.info("🧠 Запуск агента (sync) для документа: {}", jobId);
        String context = getDocumentContext(jobId);
        return chatModel.generate(AiPrompts.agentInstruction(context, instruction));
    }

    public SseEmitter executeAgentStream(String jobId, String instruction) {
        log.info("🌊 Запуск агента (stream) для документа: {}", jobId);
        String context = getDocumentContext(jobId);
        return sseEmitterFactory.stream(streamingChatModel, AiPrompts.agentInstruction(context, instruction), "agent/" + jobId);
    }

    private String getDocumentContext(String jobId) {
        String json = redisTemplate.opsForValue().get("job:" + jobId);
        if (json == null) {
            return "Контекст документа не найден. Возможно, файл еще не обработан или удален.";
        }
        // Защита от переполнения контекста быстрой модели
        if (json.length() > 25000) {
            return json.substring(0, 25000) + "\n...[ТЕКСТ ОБРЕЗАН]...";
        }
        return json;
    }
}