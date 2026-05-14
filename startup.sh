#!/bin/bash
# ============================================================
# startup.sh - Script de arranque para Azure App Service
# ============================================================
# Uso: configurar como "Startup Command" en App Service:
#   bash /home/site/wwwroot/startup.sh
#
# Alternativa sin este script: definir en App Service Settings:
#   JAVA_TOOL_OPTIONS = -javaagent:/home/site/wwwroot/otel-javaagent.jar -Xmx512m
# ============================================================

AGENT_PATH="/home/site/wwwroot/target/otel-javaagent.jar"
APP_JAR="/home/site/wwwroot/target/orders-spring-app-1.0.0.jar"

echo "[startup] Iniciando orders-spring-app..."
echo "[startup] Servicio OTEL: ${OTEL_SERVICE_NAME:-orders-spring-app}"
echo "[startup] Endpoint OTEL: ${OTEL_EXPORTER_OTLP_ENDPOINT:-no configurado}"

if [ ! -f "$AGENT_PATH" ]; then
    echo "[startup] ADVERTENCIA: agente OTEL no encontrado en $AGENT_PATH"
    echo "[startup] Iniciando sin instrumentación OTEL"
    exec java \
        -Xmx512m \
        -Xms256m \
        -Dserver.port="${PORT:-8080}" \
        -jar "$APP_JAR"
else
    echo "[startup] Agente OTEL encontrado. Iniciando con zero-code instrumentation..."
    exec java \
        -javaagent:"$AGENT_PATH" \
        -Xmx512m \
        -Xms256m \
        -Dserver.port="${PORT:-8080}" \
        -jar "$APP_JAR"
fi
