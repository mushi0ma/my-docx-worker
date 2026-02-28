package com.example.document_parser;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DocumentParserIntegrationTest {

        @Container
        static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                        .withDatabaseName("testdb")
                        .withUsername("test")
                        .withPassword("test");

        @Container
        static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                        .withExposedPorts(6379);

        @Container
        static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3-management-alpine");

        @DynamicPropertySource
        static void configureProperties(DynamicPropertyRegistry registry) {
                registry.add("spring.datasource.url", postgres::getJdbcUrl);
                registry.add("spring.datasource.username", postgres::getUsername);
                registry.add("spring.datasource.password", postgres::getPassword);
                registry.add("spring.data.redis.host", redis::getHost);
                registry.add("spring.data.redis.port", () -> String.valueOf(redis.getMappedPort(6379)));
                registry.add("spring.rabbitmq.host", rabbit::getHost);
                registry.add("spring.rabbitmq.port", () -> String.valueOf(rabbit.getAmqpPort()));
                registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
                registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
        }

        @Autowired
        private org.springframework.web.context.WebApplicationContext context;

        private MockMvc mockMvc;

        @org.junit.jupiter.api.BeforeEach
        void setup() {
                this.mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                                .webAppContextSetup(context)
                                .build();
        }

        @Test
        void contextLoads() {
        }

        @Test
        void uploadInvalidFile_returns400() throws Exception {
                MockMultipartFile file = new MockMultipartFile(
                                "file", "test.txt", "text/plain", "content".getBytes());

                mockMvc.perform(multipart("/api/v1/documents/parse").file(file))
                                .andExpect(status().isBadRequest());
        }

        @Test
        void getUnknownJobId_returns404() throws Exception {
                mockMvc.perform(get("/api/v1/documents/00000000-0000-0000-0000-000000000000"))
                                .andExpect(status().isNotFound());
        }

        @Test
        void getInvalidJobId_returns400() throws Exception {
                mockMvc.perform(get("/api/v1/documents/not-a-uuid"))
                                .andExpect(status().isBadRequest());
        }

        @Test
        void actuatorHealth_isUp() throws Exception {
                mockMvc.perform(get("/actuator/health"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("UP"));
        }

        @Test
        void getStatus_unknownJob_returnsNotFound() throws Exception {
                mockMvc.perform(get("/api/v1/documents/00000000-0000-0000-0000-000000000000/status"))
                                .andExpect(status().isNotFound());
        }
}