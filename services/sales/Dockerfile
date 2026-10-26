# syntax=docker/dockerfile:1

# ---------- Etapa 1: compilación (Maven + JDK) ----------
FROM maven:3.9.16-eclipse-temurin-25 AS build
# La imagen define MAVEN_CONFIG; se vacía para que mvnw no lo reciba como argumento.
ENV MAVEN_CONFIG=""
WORKDIR /workspace

# Primero solo lo necesario para resolver dependencias: esta capa se cachea
# y no se repite mientras el pom.xml no cambie.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Los tests se ejecutan con ./scripts/mvn.sh (necesitan Docker para Testcontainers).
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests

# ---------- Etapa 2: ejecución (solo JRE, usuario no root) ----------
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/sales.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
