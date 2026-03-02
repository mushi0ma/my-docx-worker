package com.example.document_parser;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Точка входа приложения.
 *
 * @EnableAsync перенесён в AsyncConfig — там же находятся thread pool'ы,
 * что логично держать вместе. Spring подхватит его оттуда автоматически.
 *
 * @EnableScheduling нужен для FileCleanupService (@Scheduled).
 */
@SpringBootApplication
@EnableScheduling
public class DocumentParserApplication {

	public static void main(String[] args) {
		SpringApplication.run(DocumentParserApplication.class, args);
	}
}