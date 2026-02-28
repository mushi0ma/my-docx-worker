# =============================================
# ЭТАП 1: Сборка (Build stage)
# =============================================
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Копируем Maven Wrapper и pom.xml отдельно для кэширования зависимостей.
# Слой с зависимостями пересобирается только если изменился pom.xml.
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN chmod +x mvnw && ./mvnw dependency:go-offline -q

# Копируем исходный код и собираем JAR
COPY src src
RUN ./mvnw package -DskipTests -q

# =============================================
# ЭТАП 2: Runtime (финальный образ)
# =============================================
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Создаём непривилегированного пользователя — не запускаем приложение от root
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Создаём директорию для временных файлов и даём права нашему пользователю
# ПРИМЕЧАНИЕ: в docker-compose.yml нужно смонтировать volume сюда:
#   volumes:
#     - temp_docs_volume:/app/temp_docs
RUN mkdir -p /app/temp_docs && chown appuser:appgroup /app/temp_docs

# Копируем JAR из builder-этапа
COPY --from=builder /app/target/*.jar app.jar

# Переключаемся на непривилегированного пользователя
USER appuser

EXPOSE 8081

# Запуск с оптимальными флагами для контейнеров
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]