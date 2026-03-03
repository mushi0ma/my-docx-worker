package com.example.document_parser.service;

import com.example.document_parser.dto.DocumentMetadataResponse.DocumentBlock;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class ImageExtractor {

    private static final Logger log = LoggerFactory.getLogger(ImageExtractor.class);
    private static final Path IMAGE_STORAGE = Paths.get(
            System.getenv().getOrDefault("IMAGE_STORAGE_PATH", "/app/images"));

    public DocumentBlock extract(XWPFPicture picture, String jobId) {
        try {
            XWPFPictureData picData = picture.getPictureData();
            byte[] bytes = picData.getData();
            String extension = picData.suggestFileExtension();
            String rawName = picture.getDescription();

            String fileName;
            if (rawName == null || rawName.trim().isEmpty()) {
                fileName = "image_" + System.currentTimeMillis() + "." + extension;
            } else {
                // Sanitize: remove characters unsafe for filesystems
                String sanitized = rawName.replaceAll("[\\\\/:*?\"<>|\\n\\r]", "_").trim();
                // Truncate to avoid "File name too long" (max ~255 bytes on most FS)
                if (sanitized.length() > 100) {
                    sanitized = sanitized.substring(0, 100);
                }
                fileName = sanitized.endsWith("." + extension) ? sanitized : sanitized + "." + extension;
            }

            String cleanJobId = jobId.replace("-", "");
            String shard1 = cleanJobId.substring(0, 2);
            String shard2 = cleanJobId.substring(2, 4);

            Path jobImageDir = IMAGE_STORAGE.resolve(shard1).resolve(shard2).resolve(jobId);
            Files.createDirectories(jobImageDir);

            Path targetPath = jobImageDir.resolve(fileName);
            Files.write(targetPath, bytes);

            String imageUrl = "/api/v1/documents/" + jobId + "/images/" + fileName;

            DocumentBlock imgBlock = new DocumentBlock();
            imgBlock.setType("IMAGE");
            imgBlock.setImageUrl(imageUrl);
            imgBlock.setImageName(fileName); // ИСПРАВЛЕНО: использование правильного поля DTO

            return imgBlock;
        } catch (Exception e) {
            log.warn("[{}] Ошибка при извлечении изображения: {}", jobId, e.getMessage());
            return null;
        }
    }
}