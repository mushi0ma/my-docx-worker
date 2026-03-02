package com.example.document_parser;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class DocumentParserApplication {

	public static void main(String[] args) {
		SpringApplication.run(DocumentParserApplication.class, args);
	}

}
