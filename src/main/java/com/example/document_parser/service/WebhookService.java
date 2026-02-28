package com.example.document_parser.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import java.util.Map;
import java.util.HashMap;

@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private final RestTemplate restTemplate;

    public WebhookService() {
        this.restTemplate = new RestTemplate();
    }

    public void sendWebhook(String webhookUrl, String jobId, String status, String message, String resultUrl) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> payload = new HashMap<>();
            payload.put("jobId", jobId);
            payload.put("status", status);
            payload.put("message", message);
            if (resultUrl != null) {
                payload.put("resultUrl", resultUrl);
            }

            HttpEntity<Map<String, String>> request = new HttpEntity<>(payload, headers);
            restTemplate.postForEntity(webhookUrl, request, String.class);
            log.info("🌐 Webhook sent successfully to {} for jobId: {}", webhookUrl, jobId);
        } catch (Exception e) {
            log.warn("⚠️ Failed to send webhook to {}: {}", webhookUrl, e.getMessage());
        }
    }
}
