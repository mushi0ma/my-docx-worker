package com.example.document_parser.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Webhook-уведомления о завершении задач.
 *
 * Защита от SSRF:
 * 1. Разрешены только схемы http/https
 * 2. DNS-резолюция URL и проверка IP:
 *    - loopback (127.x.x.x, ::1)
 *    - site-local / private (10.x, 172.16-31.x, 192.168.x)
 *    - link-local (169.254.x.x)
 * 3. Payload не логируется целиком — нет утечки стектрейсов
 * 4. Ошибки не пробрасываются — webhook best-effort
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final RestClient restClient;

    public WebhookService() {
        this.restClient = RestClient.builder()
                .defaultHeader("User-Agent", "document-parser/1.0")
                .build();
    }

    public void sendWebhook(String webhookUrl, String jobId, String status,
                            String message, String resultUrl) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        if (!isUrlSafe(webhookUrl, jobId)) {
            return; // причина уже залогирована внутри
        }

        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("jobId", jobId);
            payload.put("status", status);
            if (message != null) payload.put("message", message);
            if (resultUrl != null) payload.put("resultUrl", resultUrl);

            restClient.post()
                    .uri(URI.create(webhookUrl))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Webhook sent. jobId={}, status={}", jobId, status);

        } catch (Exception e) {
            log.warn("Failed to send webhook. jobId={}, error={}", jobId, e.getMessage());
        }
    }

    /**
     * Проверяет URL на безопасность.
     * Возвращает false (и логирует причину) если URL небезопасен.
     */
    private boolean isUrlSafe(String url, String jobId) {
        // 1. Только http/https
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            log.warn("Webhook rejected: invalid scheme. jobId={}", jobId);
            return false;
        }

        // 2. DNS-резолюция + проверка приватных IP
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                log.warn("Webhook rejected: missing host. jobId={}", jobId);
                return false;
            }

            InetAddress address = InetAddress.getByName(host);

            if (address.isLoopbackAddress()) {
                log.warn("Webhook rejected: loopback address. jobId={}, host={}", jobId, host);
                return false;
            }
            if (address.isSiteLocalAddress()) {
                log.warn("Webhook rejected: private/site-local address. jobId={}, host={}", jobId, host);
                return false;
            }
            if (address.isLinkLocalAddress()) {
                log.warn("Webhook rejected: link-local address. jobId={}, host={}", jobId, host);
                return false;
            }
            if (address.isAnyLocalAddress()) {
                log.warn("Webhook rejected: any-local address. jobId={}, host={}", jobId, host);
                return false;
            }

        } catch (Exception e) {
            log.warn("Webhook rejected: failed to resolve host. jobId={}, error={}", jobId, e.getMessage());
            return false;
        }

        return true;
    }
}