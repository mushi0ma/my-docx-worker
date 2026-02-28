package com.example.document_parser.service;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DocumentProducer {

    private final RabbitTemplate rabbitTemplate;

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
        // Отправляем ID задачи и тип в очередь: "jobId|taskType"
        String message = jobId + "|" + taskType;
        rabbitTemplate.convertAndSend(exchange, routingKey, message);
        System.out.println("Sent Job to RabbitMQ: " + message);
    }
}