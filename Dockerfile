FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src src
RUN ./mvnw package -DskipTests --batch-mode

# Minimal Secure Runtime for RelayHub
FROM eclipse-temurin:21-jre-alpine AS runner
WORKDIR /app

# Add unprivileged system user
RUN addgroup -S -g 10001 relayhub && \
    adduser -S -u 10001 -G relayhub -s /bin/sh relayhub

# Copy application JAR
COPY --from=build /build/target/relayhub-service-1.0.0-SNAPSHOT.jar /app/relayhub.jar

RUN chown -R 10001:10001 /app

USER 10001:10001

EXPOSE 8080

HEALTHCHECK --interval=5s --timeout=3s --retries=10 --start-period=10s \
  CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/relayhub.jar \"$@\"", "relayhub"]
