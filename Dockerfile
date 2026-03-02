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

# Timezone — устанавливаем Asia/Almaty (UTC+5) на уровне образа.
# docker-compose TZ переменная переопределяет это если нужно.
ENV TZ=Asia/Almaty
RUN apt-get update -qq && apt-get install -y --no-install-recommends tzdata \
    && ln -snf /usr/share/zoneinfo/$TZ /etc/localtime \
    && echo $TZ > /etc/timezone \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

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
# -Duser.timezone дублирует TZ переменную — защита на уровне JVM
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-Duser.timezone=Asia/Almaty", \
  "-jar", "app.jar"]