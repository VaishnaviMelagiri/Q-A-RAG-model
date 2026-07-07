# syntax=docker/dockerfile:1

# --- build stage: compile the Spring Boot fat jar (Java 21) ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Resolve dependencies first so this layer is cached until pom.xml changes.
COPY pom.xml .
RUN mvn -B dependency:go-offline
# Then compile + package. Tests run in CI (the test gate), not in the image build.
COPY src ./src
RUN mvn -B -DskipTests package

# --- run stage: slim JRE, non-root ---
FROM eclipse-temurin:21-jre AS run
WORKDIR /app
RUN useradd -r -u 1001 appuser
COPY --from=build /build/target/*.jar /app/app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
