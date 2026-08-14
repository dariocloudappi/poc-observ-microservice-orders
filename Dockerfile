# ============================================================
# Build stage
# ============================================================
FROM eclipse-temurin:17-jdk-alpine AS build

WORKDIR /build

COPY pom.xml .
COPY src ./src

# Descarga dependencias y construye el JAR (incluye descarga del agente OTEL)
RUN apk add --no-cache maven && \
    mvn clean package -DskipTests

# ============================================================
# Runtime stage
# ============================================================
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copiar el JAR de la aplicacion y el agente OTEL desde el build stage.
# El directorio de salida de Maven es target/, no targetv6/.
COPY --from=build /build/target/orders-spring-app-1.0.0.jar app.jar
COPY --from=build /build/target/otel-javaagent.jar otel-javaagent.jar

# El agente OTEL se activa vía JAVA_TOOL_OPTIONS sin modificar el código
# Las variables OTEL_* se inyectan en tiempo de ejecución (docker run -e o kubernetes env)
ENV JAVA_TOOL_OPTIONS="-javaagent:/app/otel-javaagent.jar -Xmx512m -Xms256m"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
