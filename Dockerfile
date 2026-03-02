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
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Создаём непривилегированного пользователя (синтаксис для Ubuntu/Debian)
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

# Создаём директории для временных файлов и картинок, даём права нашему пользователю
RUN mkdir -p /app/temp_docs /app/images && chown -R appuser:appgroup /app

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