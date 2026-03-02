package com.example.document_parser.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DocumentProducer {

    private final RabbitTemplate rabbitTemplate;
    private static final Logger log = LoggerFactory.getLogger(DocumentProducer.class);

    @Value("${app.rabbitmq.exchange}")
    private String exchange;

    @Value("${app.rabbitmq.routingkey}")
    private String routingKey;

    public DocumentProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void sendToQueue(String jobId) {
        sendToQueue(jobId, "PARSE");
    }

    public void sendToQueue(String jobId, String taskType) {
        String message = jobId + "|" + taskType;
        rabbitTemplate.convertAndSend(exchange, routingKey, message);
        // Логируем jobId и taskType раздельно — так их проще фильтровать/маскировать
        log.info("Job sent to RabbitMQ queue. jobId={}, taskType={}", jobId, taskType);
    }
}