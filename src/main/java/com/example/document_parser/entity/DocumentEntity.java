package com.example.document_parser.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "parsed_documents")
public class DocumentEntity {

    @Id
    private String jobId;

    private String status;

    // Указываем тип TEXT, так как JSON может быть большим
    @Column(columnDefinition = "TEXT")
    private String resultJson;

    private LocalDateTime createdAt;

    // Обязательный пустой конструктор для Spring
    public DocumentEntity() {}

    public DocumentEntity(String jobId, String status, String resultJson) {
        this.jobId = jobId;
        this.status = status;
        this.resultJson = resultJson;
        this.createdAt = LocalDateTime.now();
    }

    // Геттеры и Сеттеры
    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}