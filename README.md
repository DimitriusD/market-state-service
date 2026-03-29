# Hexagonal Service Template

A Spring Boot microservice template following **hexagonal architecture** (ports & adapters) with Gradle multi-module setup.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    infrastructure/app                            │
│              (Spring Boot, wiring, composition)                  │
├──────────┬──────────────────┬──────────────┬────────────────────┤
│ rest-api │ jdbc-storage-    │ event-       │  (your custom      │
│ (HTTP    │ adapter          │ adapter      │   adapters...)      │
│  driving │ (Postgres driven │ (Kafka driven│                    │
│  adapter)│  adapter)        │  adapter)    │                    │
├──────────┴──────────────────┴──────────────┴────────────────────┤
│                        application                               │
│     domain/model  │  port/input  │  port/output  │  service     │
│     domain/exception                                             │
└─────────────────────────────────────────────────────────────────┘
```

### Modules

| Module | Purpose |
|--------|---------|
| `application` | Domain models, input/output ports (interfaces), application services. No framework dependencies. |
| `infrastructure/app` | Spring Boot entrypoint. Wires ports to adapter implementations via `InfrastructureConfig`. |
| `infrastructure/rest-api` | HTTP driving adapter. OpenAPI-first code generation + hand-written controllers and MapStruct mappers. |
| `infrastructure/jdbc-storage-adapter` | PostgreSQL persistence via Spring Data JDBC. Flyway migrations. |
| `infrastructure/event-adapter` | Kafka event publishing. Implements output ports for domain events. |

### Key patterns

- **Ports & Adapters**: domain logic in `application` depends on nothing; adapters implement ports
- **OpenAPI-first**: REST API defined in `openapi.yaml`, interfaces generated at build time
- **MapStruct**: type-safe mapping between layers (web DTOs <-> domain <-> entities)
- **Flyway**: versioned database migrations
- **Version catalog**: all dependency versions centralized in `gradle/libs.versions.toml`

## Tech Stack

- Java 21
- Spring Boot 3.3.x
- Gradle 9.x (Kotlin DSL)
- PostgreSQL 17
- Apache Kafka
- Flyway
- MapStruct + Lombok
- OpenAPI Generator
- Testcontainers

## Quick Start

### 1. Initialize for your project

Run the init script to rename packages and project:

```powershell
# PowerShell
.\init.ps1 -ProjectName "order-service" -Group "com.mycompany" -BasePackage "com.mycompany.orders"
```

```bash
# Bash
./init.sh order-service com.mycompany com.mycompany.orders
```

### 2. Start infrastructure

```bash
docker compose up -d
```

### 3. Build & run

```bash
./gradlew build
./gradlew :infrastructure:app:bootRun
```

### 4. Test the API

```bash
# Create an item
curl -X POST http://localhost:8080/api/v1/items \
  -H "Content-Type: application/json" \
  -d '{"name": "Test Item", "description": "A test item"}'

# List items
curl http://localhost:8080/api/v1/items
```

## Creating a New Service

1. Copy this template (or use GitHub "Use this template")
2. Run `init.ps1` / `init.sh` with your project parameters
3. Replace the sample `Item` domain with your actual domain model
4. Adjust ports, services, and adapters for your use case
5. Update `openapi.yaml` with your actual API contract
6. Modify Flyway migrations for your schema
7. Add/remove infrastructure adapters as needed

## Project Structure

```
hexagonal-service-template/
├── build.gradle.kts                 # Root build: Java 21, JUnit Platform
├── settings.gradle.kts              # Module declarations
├── gradle/libs.versions.toml        # Centralized dependency versions
├── docker-compose.yml               # Postgres + Kafka + Kafka UI
├── application/
│   ├── build.gradle.kts             # java-library, no Spring Boot
│   └── src/main/java/.../application/
│       ├── domain/
│       │   ├── model/Item.java
│       │   └── exception/
│       ├── port/
│       │   ├── input/ItemService.java
│       │   └── output/ItemStoragePort.java, ItemEventPublisherPort.java
│       └── service/ItemServiceImpl.java
├── infrastructure/
│   ├── app/
│   │   ├── build.gradle.kts         # Spring Boot plugin, pulls all modules
│   │   └── src/main/
│   │       ├── java/.../Application.java, config/InfrastructureConfig.java
│   │       └── resources/application.yml
│   ├── rest-api/
│   │   ├── build.gradle.kts         # OpenAPI Generator plugin
│   │   └── src/main/
│   │       ├── java/.../rest/ItemController.java, advice/, mapper/
│   │       └── resources/openapi/openapi.yaml, schemas/
│   ├── jdbc-storage-adapter/
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── java/.../entity/, repository/, mapper/, storage/
│   │       └── resources/db/migrations/
│   └── event-adapter/
│       ├── build.gradle.kts
│       └── src/main/java/.../event/
│           ├── EventAdapterConfig.java
│           ├── KafkaItemEventPublisher.java
│           └── ItemEvent.java
├── init.ps1                          # PowerShell init script
└── init.sh                           # Bash init script
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `APP_DB_URL` | `jdbc:postgresql://localhost:5432/appdb` | Database URL |
| `APP_DB_USERNAME` | `appuser` | Database username |
| `APP_DB_PASSWORD` | `apppass` | Database password |
| `APP_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers |
| `APP_KAFKA_TOPIC_ITEM_EVENTS` | `service.item.events.v1` | Kafka topic for item events |
