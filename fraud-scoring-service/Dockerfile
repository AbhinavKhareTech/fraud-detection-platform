# Multi-stage build for the fraud scoring service
# Stage 1: Build with Maven
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app
COPY pom.xml .
# Download dependencies first (cached layer)
RUN mvn dependency:go-offline -B 2>/dev/null || true

COPY src ./src
RUN mvn clean package -DskipTests -B 2>/dev/null || true

# Stage 2: Runtime image
FROM eclipse-temurin:21-jre-alpine

# Security: run as non-root
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy the built JAR
COPY --from=builder /app/target/fraud-scoring-service-1.0.0.jar app.jar

# JVM tuning for payment processing workload:
# - UseZGC: Low-pause garbage collector (< 1ms pauses, critical for auth path latency)
# - MaxGCPauseMillis: Targets sub-millisecond GC pauses
# - XX:+UseStringDeduplication: Reduces heap for repeated card tokens
# - Xmx512m: Sized for feature vector caching and model inference
ENV JAVA_OPTS="-XX:+UseZGC \
    -XX:MaxGCPauseMillis=1 \
    -XX:+UseStringDeduplication \
    -Xms256m -Xmx512m \
    -Djava.security.egd=file:/dev/./urandom"

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
    CMD wget -q --spider http://localhost:8080/api/v1/fraud/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
