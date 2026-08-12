# ============================================================
# Stage 1: Build the Spring Boot application
# ============================================================
FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

# Copy Maven wrapper and project files first
# This allows Docker to cache dependency downloads.
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Make Maven wrapper executable
RUN chmod +x mvnw

# Download dependencies
RUN ./mvnw dependency:go-offline -B

# Copy application source
COPY src src

# Build the application
RUN ./mvnw clean package -DskipTests

# Keep only the executable Spring Boot JAR
RUN JAR_FILE=$(find target -maxdepth 1 \
        -type f \
        -name "*.jar" \
        ! -name "*.original" \
        | head -n 1) \
    && test -n "$JAR_FILE" \
    && cp "$JAR_FILE" /app/app.jar


# ============================================================
# Stage 2: Runtime image
# ============================================================
FROM eclipse-temurin:21-jre

WORKDIR /app

# Install curl for Docker healthcheck
USER root

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Create non-root application user
RUN useradd \
    --system \
    --uid 1001 \
    --create-home \
    appuser

# Copy executable JAR from build stage
COPY --from=build /app/app.jar /app/app.jar

# Give application user ownership
RUN chown -R appuser:appuser /app

# Run application as non-root user
USER appuser

EXPOSE 8080

# Start Spring Boot
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]