# Multi-stage build: compiles with Maven + JDK 21 and ships a minimal Temurin 21 JRE image.
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Cache dependencies before copying source so incremental rebuilds stay fast.
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn package -DskipTests -B

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as non-root user inside the container for least-privilege security hygiene.
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=build /app/target/realtime-chat-backend-1.0.0.jar app.jar
USER appuser

EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
