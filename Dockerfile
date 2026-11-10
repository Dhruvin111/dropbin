# ─── Build Stage ─────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17-alpine AS builder
WORKDIR /build

# Pre-cache Maven dependencies
COPY pom.xml .
RUN mvn dependency:resolve-plugins dependency:resolve -B || true

# Copy source code and build production jar
COPY src ./src
RUN mvn clean package -DskipTests -B

# ─── Runtime Stage ───────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Run as non-root user for cloud container security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Create upload directory with non-root ownership
RUN mkdir -p /app/uploads && chown -R appuser:appgroup /app

# Copy compiled jar from build stage
COPY --from=builder --chown=appuser:appgroup /build/target/*.jar /app/app.jar

USER appuser

ENV PORT=8080
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

