# syntax=docker/dockerfile:1

# ---------- Etapa 1: compilación (JDK + Maven Wrapper) ----------
# Solo el JDK: Maven lo descarga ./mvnw (versión en .mvn/wrapper, la misma que en local y en la CI). Java 25 (LTS)
# va en la versión del tag, con el parche fijado: Dependabot propone los parches y no los cambios mayores (ADR 0016).
FROM eclipse-temurin:25.0.4.1_1-jdk-alpine AS build
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
# El mismo parche de Java que la etapa de compilación.
FROM eclipse-temurin:25.0.4.1_1-jre-alpine
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/invoicing.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
