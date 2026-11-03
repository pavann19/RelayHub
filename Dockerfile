# Multi-Stage Production Dockerfile for RelayHub

# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Copy Maven Wrapper and settings
COPY .mvn .mvn
COPY mvnw mvnw
COPY pom.xml pom.xml

# Download dependencies offline/pre-fetch layer
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B || true

# Copy source and compile application
COPY src src
RUN ./mvnw clean package -DskipTests --batch-mode

# Stage 2: Minimal Secure Runtime
FROM eclipse-temurin:21-jre-alpine AS runner
WORKDIR /app

# Add unprivileged system user
RUN addgroup -S -g 10001 relayhub && \
    adduser -S -u 10001 -G relayhub -s /bin/sh relayhub

# Copy application JAR from builder
COPY --from=builder --chown=relayhub:relayhub /workspace/target/relayhub-service-*.jar /app/relayhub.jar

USER 10001:10001

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --retries=5 --start-period=15s \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health/liveness || exit 1

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/relayhub.jar"]
