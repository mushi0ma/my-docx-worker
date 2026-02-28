package com.example.document_parser.entity;

import com.example.document_parser.model.JobStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "parsed_documents")
@Getter
@Setter
@NoArgsConstructor
public class DocumentEntity {

    @Id
    private String jobId;

    // ИСПРАВЛЕНО: enum вместо строки — опечатка теперь не компилируется
    @Enumerated(EnumType.STRING)
    private JobStatus status;

    private String originalFileName;

    @Column
    private String taskType; // "PARSE" or "GENERATE"

    @Column
    private String webhookUrl;

    @Column(columnDefinition = "TEXT")
    private String resultJson;

    // НОВОЕ: прогресс парсинга 0–100 для отображения прогресс-бара на фронте
    @Column(nullable = false)
    private int progress = 0;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public DocumentEntity(String jobId, JobStatus status, String originalFileName) {
        this.jobId = jobId;
        this.status = status;
        this.originalFileName = originalFileName;
        this.taskType = "PARSE";
        this.progress = status == JobStatus.SUCCESS ? 100 : 0;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public DocumentEntity(String jobId, JobStatus status, String originalFileName, String taskType, String webhookUrl) {
        this(jobId, status, originalFileName);
        this.taskType = taskType;
        this.webhookUrl = webhookUrl;
    }

    public DocumentEntity(String jobId, JobStatus status, String originalFileName, String resultJson) {
        this(jobId, status, originalFileName);
        this.resultJson = resultJson;
        this.progress = status == JobStatus.SUCCESS ? 100 : 0;
    }

    public void updateProgress(int progress, JobStatus status) {
        this.progress = progress;
        this.status = status;
        this.updatedAt = LocalDateTime.now();
    }
}