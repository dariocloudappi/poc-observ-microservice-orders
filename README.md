# poc-microservice-orders

Microservicio de pedidos (Orders) construido con **Spring Boot 3.2.5**, **Azure SQL Database** (SQL Server) y **OpenTelemetry zero-code** hacia New Relic.

## Arquitectura

```
Cliente HTTP
     │
     ▼
┌─────────────────────────────────────────┐
│          Azure App Service (Linux)      │
│          Java 17 + Spring Boot          │
│                                         │
│  ┌────────────────────────────────────┐ │
│  │  orders-spring-app-1.0.0.jar       │ │
│  │  + otel-javaagent.jar (zero-code)  │ │
│  └────────────────────────────────────┘ │
└────────────┬──────────────┬─────────────┘
             │              │
             ▼              ▼
    Azure SQL Database   New Relic OTLP
    (orders, order_items)  (trazas, métricas, logs)
```

## Estructura del proyecto

```
src/main/java/com/example/ordersapp/
├── OrdersApplication.java
├── config/
│   ├── SecurityConfig.java          # Basic Auth
│   ├── TraceIdInterceptor.java      # Añade x-trace-id al response
│   └── WebConfig.java               # Registro del interceptor
├── controller/
│   └── OrderController.java         # Endpoints REST
├── model/
│   ├── Order.java                   # Entidad JPA (tabla: orders)
│   ├── OrderItem.java               # Entidad JPA (tabla: order_items)
│   └── OrderStatus.java             # Enum de estados
├── repository/
│   └── OrderRepository.java         # JpaRepository<Order, UUID>
├── service/
│   ├── OrderService.java            # Lógica de negocio
│   └── UserValidationService.java   # Cliente HTTP al microservicio de usuarios
├── exception/
│   ├── GlobalExceptionHandler.java
│   ├── ResourceNotFoundException.java
│   ├── UserNotFoundException.java
│   └── UsersServiceUnavailableException.java
├── filter/
│   └── RequestLoggingFilter.java    # Log de requests/responses
└── client/dto/
    ├── UserDto.java
    ├── UserSingleEnvelope.java
    └── ApiErrorDto.java
```

## Pre-requisitos

- JDK 17+
- Maven 3.8+
- Azure SQL Database **o** SQL Server local (Docker)
- New Relic License Key (para observabilidad)
- Azure CLI (para despliegue en App Service)

## Base de datos SQL

### Crear tablas

Ejecuta el script [create-tables.sql](create-tables.sql) contra tu base de datos antes de arrancar la aplicación.

```bash
# Azure SQL Database via sqlcmd
sqlcmd -S tu-servidor.database.windows.net -d ordersdb -U tu_usuario -P tu_contraseña -i create-tables.sql

# SQL Server local
sqlcmd -S localhost -d ordersdb -U sa -P tu_contraseña -i create-tables.sql
```

### SQL Server local con Docker

```bash
docker run -e "ACCEPT_EULA=Y" -e "SA_PASSWORD=Tu_Password_123!" \
  -p 1433:1433 --name sqlserver \
  -d mcr.microsoft.com/mssql/server:2022-latest
```

Crear base de datos:
```sql
CREATE DATABASE ordersdb;
```

### Crear Azure SQL Database

```bash
az sql server create \
  --name tu-servidor \
  --resource-group tu-grupo \
  --location eastus \
  --admin-user sqladmin \
  --admin-password "TuPassword123!"

az sql db create \
  --resource-group tu-grupo \
  --server tu-servidor \
  --name ordersdb \
  --service-objective S0
```

## Variables de entorno

Copia `.env.example` a `.env` y completa los valores:

| Variable | Descripción | Ejemplo |
|---|---|---|
| `SQL_URL` | JDBC URL de la base de datos | `jdbc:sqlserver://...` |
| `SQL_USERNAME` | Usuario de la base de datos | `sqladmin` |
| `SQL_PASSWORD` | Contraseña de la base de datos | `...` |
| `USERS_SERVICE_URL` | URL del microservicio de usuarios | `http://localhost:8081` |
| `USERS_SERVICE_USERNAME` | Usuario para llamar al servicio de usuarios | `admin` |
| `USERS_SERVICE_PASSWORD` | Contraseña del servicio de usuarios | `password` |
| `API_USERNAME` | Usuario Basic Auth de esta API | `admin` |
| `API_PASSWORD` | Contraseña Basic Auth de esta API | `password` |
| `OTEL_SERVICE_NAME` | Nombre del servicio en New Relic | `orders-spring-app` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Endpoint OTLP New Relic | `https://otlp.nr-data.net:4318` |
| `OTEL_EXPORTER_OTLP_HEADERS` | License Key de New Relic | `api-key=...` |
| `PORT` | Puerto del servidor | `8080` |
| `ENVIRONMENT` | Entorno (local/staging/prod) | `local` |

## Desarrollo local

```bash
# 1. Clonar el repositorio
git clone <repo-url>
cd poc-microservice-orders

# 2. Copiar y configurar variables de entorno
cp .env.example .env
# Editar .env con tus valores de SQL Server

# 3. Compilar (descarga también el agente OTEL)
mvn clean package -DskipTests

# 4. Ejecutar con agente OTEL (zero-code instrumentation)
export JAVA_TOOL_OPTIONS="-javaagent:./targetv6/otel-javaagent.jar"
source .env  # o cargar las variables manualmente
java -jar target/orders-spring-app-1.0.0.jar
```

### Sin agente OTEL (solo desarrollo)

```bash
mvn spring-boot:run \
  -DSQL_URL="jdbc:sqlserver://localhost:1433;database=ordersdb;encrypt=false;trustServerCertificate=true" \
  -DSQL_USERNAME=sa \
  -DSQL_PASSWORD=Tu_Password_123!
```

## Build

```bash
mvn clean package -DskipTests
# Genera: targetv6/orders-spring-app-1.0.0.jar
#         targetv6/otel-javaagent.jar
```

## Despliegue en Azure App Service

### Opción 1: Maven plugin

```bash
export AZURE_SUBSCRIPTION_ID=<subscription-id>
export AZURE_RESOURCE_GROUP=<resource-group>
export AZURE_APP_NAME=<app-name>
export AZURE_REGION=eastus

mvn azure-webapp:deploy
```

### Opción 2: Variables de entorno en App Service

```bash
az webapp config appsettings set \
  --resource-group tu-grupo \
  --name tu-app \
  --settings \
    SQL_URL="jdbc:sqlserver://tu-servidor.database.windows.net:1433;database=ordersdb;encrypt=true;trustServerCertificate=false;loginTimeout=30" \
    SQL_USERNAME="sqladmin" \
    SQL_PASSWORD="TuPassword123!" \
    OTEL_SERVICE_NAME="orders-spring-app" \
    OTEL_EXPORTER_OTLP_ENDPOINT="https://otlp.nr-data.net:4318" \
    OTEL_EXPORTER_OTLP_HEADERS="api-key=TU_LICENSE_KEY" \
    OTEL_EXPORTER_OTLP_PROTOCOL="http/protobuf" \
    OTEL_TRACES_EXPORTER="otlp" \
    OTEL_METRICS_EXPORTER="otlp" \
    OTEL_LOGS_EXPORTER="otlp" \
    JAVA_TOOL_OPTIONS="-javaagent:/home/site/wwwroot/otel-javaagent.jar -Xmx512m" \
    ENVIRONMENT="production"
```

### Startup script

Configura `bash /home/site/wwwroot/startup.sh` como "Startup Command" en App Service.

## Docker

```bash
# Build
docker build -t orders-spring-app:1.0.0 .

# Run
docker run -p 8080:8080 \
  -e SQL_URL="jdbc:sqlserver://host.docker.internal:1433;database=ordersdb;encrypt=false;trustServerCertificate=true" \
  -e SQL_USERNAME=sa \
  -e SQL_PASSWORD=Tu_Password_123! \
  -e OTEL_SERVICE_NAME=orders-spring-app \
  -e OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp.nr-data.net:4318 \
  -e OTEL_EXPORTER_OTLP_HEADERS="api-key=TU_LICENSE_KEY" \
  -e OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf \
  -e OTEL_TRACES_EXPORTER=otlp \
  -e OTEL_METRICS_EXPORTER=otlp \
  -e OTEL_LOGS_EXPORTER=otlp \
  orders-spring-app:1.0.0
```

## API Endpoints

Base URL: `http://localhost:8080`
Autenticación: Basic Auth (`API_USERNAME` / `API_PASSWORD`)

| Método | Endpoint | Descripción |
|---|---|---|
| `GET` | `/users/{userId}/orders` | Listar pedidos del usuario |
| `GET` | `/users/{userId}/orders?status=PENDING` | Filtrar por estado |
| `GET` | `/users/{userId}/orders/{orderId}` | Obtener pedido por ID |
| `POST` | `/users/{userId}/orders` | Crear pedido |
| `PUT` | `/users/{userId}/orders/{orderId}` | Actualizar pedido |
| `DELETE` | `/users/{userId}/orders/{orderId}` | Eliminar pedido |
| `GET` | `/actuator/health` | Health check (sin auth) |

### Estados de un pedido

`PENDING` → `CONFIRMED` → `SHIPPED` → `DELIVERED` / `CANCELLED`

### Ejemplo: Crear pedido

```bash
curl -X POST http://localhost:8080/users/user-123/orders \
  -u admin:password \
  -H "Content-Type: application/json" \
  -d '{
    "items": [
      {
        "productId": "prod-001",
        "productName": "Laptop Pro",
        "quantity": 1,
        "unitPrice": 1299.99
      }
    ]
  }'
```

Respuesta `201 Created`:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "userId": "user-123",
  "items": [
    {
      "id": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
      "productId": "prod-001",
      "productName": "Laptop Pro",
      "quantity": 1,
      "unitPrice": 1299.99
    }
  ],
  "totalAmount": 1299.99,
  "status": "PENDING",
  "createdAt": "2026-05-07T10:30:00",
  "updatedAt": "2026-05-07T10:30:00"
}
```

### Ejemplo: Listar pedidos

```bash
curl http://localhost:8080/users/user-123/orders \
  -u admin:password
```

### Ejemplo: Actualizar estado

```bash
curl -X PUT http://localhost:8080/users/user-123/orders/550e8400-e29b-41d4-a716-446655440000 \
  -u admin:password \
  -H "Content-Type: application/json" \
  -d '{
    "status": "CONFIRMED",
    "items": [
      {
        "productId": "prod-001",
        "productName": "Laptop Pro",
        "quantity": 1,
        "unitPrice": 1299.99
      }
    ]
  }'
```

## OpenTelemetry (zero-code)

El agente OTEL instrumenta automáticamente sin modificar el código:

- **Trazas**: HTTP requests, queries JDBC/SQL Server
- **Métricas**: JVM heap, threads, HTTP latency, connection pool
- **Logs**: Correlación con `trace_id` y `span_id` via Logback

El header `x-trace-id` se añade a todas las respuestas HTTP para facilitar la correlación desde el cliente.

### Verificar instrumentación

```bash
# Health check
curl http://localhost:8080/actuator/health

# Métricas (Prometheus)
curl -u admin:password http://localhost:8080/actuator/metrics
```

## New Relic

Una vez configuradas las variables OTEL y desplegada la aplicación:

1. **APM & Services** → busca `orders-spring-app`
2. **Distributed Tracing** → correlación entre requests y queries SQL
3. **Logs** → búsqueda por `trace.id`

Ejemplo de consulta NRQL:
```sql
SELECT average(duration.ms) FROM Span
WHERE service.name = 'orders-spring-app'
FACET db.operation
SINCE 1 hour ago
```

## Modelo de datos

Ver [create-tables.sql](create-tables.sql) para el DDL completo.

| Tabla | Descripción |
|---|---|
| `orders` | Pedidos con UUID, userId, estado y totales |
| `order_items` | Líneas de cada pedido (FK → orders.id) |

## Compatibilidad

| Componente | Versión |
|---|---|
| Java | 17 |
| Spring Boot | 3.2.5 |
| Spring Data JPA | (incluido en Boot) |
| mssql-jdbc | (gestionado por Boot) |
| OpenTelemetry Agent | 2.10.0 |
| SQL Server compatibilidad | 2019 / 2022 / Azure SQL |
