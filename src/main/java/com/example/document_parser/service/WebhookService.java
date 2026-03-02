package com.example.document_parser.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Исправления:
 * - RestTemplate заменён на RestClient (актуально для Spring Boot 3.2+)
 * - RestClient создаётся один раз как final поле, а не новый инстанс
 * - Добавлена базовая валидация webhookUrl (защита от SSRF)
 * - Логирование не раскрывает содержимое payload
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    // RestClient — современная замена RestTemplate в Spring Boot 3.2+
    private final RestClient restClient;

    public WebhookService() {
        this.restClient = RestClient.create();
    }

    public void sendWebhook(String webhookUrl, String jobId, String status, String message, String resultUrl) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        // Базовая защита от SSRF — разрешаем только http/https
        if (!webhookUrl.startsWith("http://") && !webhookUrl.startsWith("https://")) {
            log.warn("Rejected webhook with invalid scheme for jobId={}", jobId);
            return;
        }

        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("jobId", jobId);
            payload.put("status", status);
            // Не логируем message — может содержать стектрейс или чувствительные данные
            if (message != null) {
                payload.put("message", message);
            }
            if (resultUrl != null) {
                payload.put("resultUrl", resultUrl);
            }

            restClient.post()
                    .uri(URI.create(webhookUrl))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Webhook sent. jobId={}, status={}", jobId, status);

        } catch (Exception e) {
            // Не пробрасываем — webhook это best-effort уведомление, не критический путь
            log.warn("Failed to send webhook. jobId={}, error={}", jobId, e.getMessage());
        }
    }
}