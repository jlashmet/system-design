# Banking Service Architecture

This document provides a high-level overview of the Banking Service architecture, explaining the project structure, key components, and how they interact.

## Project Structure

The project follows a Domain-Driven Design (DDD) approach with a layered architecture:

```
data-aggregation/ (bounded context)
├── api/               # OpenAPI specifications and generated models
├── domain/            # Domain layer - core business logic
├── application/       # Application layer - orchestration
├── infrastructure/    # Infrastructure layer - external concerns
│   ├── common/        # Shared infrastructure
│   ├── input/         # Input adapters (REST, messaging)
│   └── output/        # Output adapters (persistence)
└── architecture/      # Architecture tests

account-verification/ (bounded context)
├── api/               # OpenAPI specifications and generated models
├── domain/            # Domain layer - core business logic
├── application/       # Application layer - orchestration
├── infrastructure/    # Infrastructure layer - external concerns
│   ├── common/        # Shared infrastructure
│   ├── input/         # Input adapters (REST, messaging)
│   └── output/        # Output adapters (persistence)
└── architecture/      # Architecture tests

financial-insights/ (bounded context)
├── api/               # OpenAPI specifications and generated models
├── domain/            # Domain layer - core business logic
├── application/       # Application layer - orchestration
├── infrastructure/    # Infrastructure layer - external concerns
│   ├── common/        # Shared infrastructure
│   ├── input/         # Input adapters (REST, messaging)
│   └── output/        # Output adapters (persistence)
└── architecture/      # Architecture tests
```

## Architectural Layers

### Domain Layer

The domain layer contains the core business logic and domain model, independent of external concerns.

**Key Components:**
- Domain Models (Transaction, etc.)
- Domain Services (TransactionService)
- Domain Events
- Repository Interfaces

### Application Layer

The application layer orchestrates the flow of the application, coordinating between domain and infrastructure.

**Key Components:**
- Handlers (orchestration)
- Application Services

### Infrastructure Layer

The infrastructure layer implements technical concerns and adapters to external systems.

**Key Components:**
- Repository Implementations
- External Service Adapters
- Controllers
- Messaging Components

## Module Dependencies

The architecture follows the Dependency Rule of Clean Architecture, where dependencies point inward toward the domain layer:

```
┌───────────────────────────────────────────────────────────────┐
│                                                               │
│  Infrastructure Layer (Input)                                 │
│  - REST Controllers                                           │
│  - Message Consumers                                          │
│                                                               │
└─────────────────────────────┬─────────────────────────────────┘
                              │
                              ▼
┌───────────────────────────────────────────────────────────────┐
│                                                               │
│  Application Layer                                            │
│  - Handlers                                                   │
│  - Orchestration                                              │
│                                                               │
└─────────────────────────────┬─────────────────────────────────┘
                              │
                              ▼
┌───────────────────────────────────────────────────────────────┐
│                                                               │
│  Domain Layer                                                 │
│  - Domain Models                                              │
│  - Domain Services                                            │
│  - Repository Interfaces                                      │
│                                                               │
└─────────────────────────────┬─────────────────────────────────┘
                              │
                              ▼
┌───────────────────────────────────────────────────────────────┐
│                                                               │
│  Infrastructure Layer (Output)                                │
│  - Repository Implementations                                 │
│  - External Service Adapters                                  │
│                                                               │
└───────────────────────────────────────────────────────────────┘
```

## Component Interaction Example

Here's an example of how components interact across layers for transaction processing:

```
┌─────────────────────┐
│                     │
│ TransactionController  <── Infrastructure Layer (Input)
│                     │
└─────────┬───────────┘
          │
          ▼
┌─────────────────────┐
│                     │
│ DataAggregationHandler  <── Application Layer
│                     │
└─────────┬───────────┘
          │
          ▼
┌─────────────────────┐
│                     │
│  TransactionService │  <── Domain Layer
│                     │
└─────────┬───────────┘
          │
          ▼
┌─────────────────────┐
│                     │
│KeyspacesTransaction │  <── Infrastructure Layer (Output)
│     Repository      │
└─────────────────────┘
```



## Key Design Principles

1. **Domain-Driven Design**: The architecture is centered around the domain model and business logic.

2. **Separation of Concerns**: Each layer has a specific responsibility:
   - Domain: Core business logic
   - Application: Orchestration
   - Infrastructure: Technical concerns

3. **Dependency Inversion**: High-level modules don't depend on low-level modules; both depend on abstractions.

4. **Clean Architecture**: The domain layer has no dependencies on external frameworks or libraries.

5. **Single Responsibility**: Each class has a single responsibility and reason to change.



## Build and Dependencies

The project uses Maven for dependency management with a hierarchical POM structure:

- Root POM: Defines common properties and dependency management
- Module POMs: Define module-specific dependencies

Java 21 is required for building and running the project.

## Maven Naming Conventions

The project follows consistent Maven coordinate naming conventions across all modules:

### groupId
- **Standard**: `com.intuit.dataexchange.banking`
- All modules inherit this groupId from the root POM
- Reflects organizational ownership and product area
- Remains stable across all submodules

### artifactId Patterns

**Aggregator POMs** (parent modules with `<packaging>pom</packaging>`):
- Pattern: `{module-name}-aggregator`
- Examples:
  - `account-verification-aggregator`
  - `data-aggregation-aggregator` (planned rename for consistency)
  - `financial-insights-aggregator`

**API Modules** (OpenAPI specifications and generated code):
- Pattern: `{module-name}-api`
- Examples:
  - `account-verification-api`
  - `data-aggregation-api` (planned rename from `data-aggregation-apispec`)
  - `financial-insights-api`

**Domain Modules**:
- Pattern: `{module-name}-domain`
- Examples:
  - `account-verification-domain`
  - `data-aggregation-domain`
  - `financial-insights-domain`

**Application Modules**:
- Pattern: `{module-name}-application`
- Examples:
  - `account-verification-application`
  - `data-aggregation-application`
  - `financial-insights-application`

**Infrastructure Aggregator**:
- Pattern: `{module-name}-infrastructure-aggregator`
- Examples:
  - `account-verification-infrastructure-aggregator`
  - `data-aggregation-infrastructure-aggregator`
  - `financial-insights-infrastructure-aggregator`

**Infrastructure Submodules**:
- Pattern: `{module-name}-infrastructure-{submodule}`
- Examples:
  - `account-verification-infrastructure-common`
  - `account-verification-infrastructure-input`
  - `account-verification-infrastructure-output`

**Architecture Modules** (architecture tests):
- Pattern: `{module-name}-architecture`
- Examples:
  - `account-verification-architecture`
  - `data-aggregation-architecture`
  - `financial-insights-architecture`

### Naming Principles

1. **Lowercase with hyphens**: All artifactIds use lowercase letters with hyphen separators
2. **Consistent suffixes**: Use standard suffixes (`-aggregator`, `-api`, `-domain`, etc.) for easy identification
3. **No redundancy**: Don't repeat the groupId information in the artifactId
4. **Descriptive**: The combination of groupId + artifactId should clearly identify the module's purpose
5. **Stable**: Avoid including version numbers, environment names, or technology names in coordinates

## Module Dependency Rules

The project enforces strict dependency rules following Clean Architecture principles. Dependencies must always point inward toward the domain layer.

### Allowed Dependencies by Module Type

#### API Module (`{module}-api`)
**Can depend on:**
- Nothing (generates POJOs and interfaces from OpenAPI specs)

**Cannot depend on:**
- Any other internal modules

**Notes:**
- Contains only OpenAPI specifications and generated code
- No business logic or implementation

---

#### Domain Module (`{module}-domain`)
**Can depend on:**
- Nothing (pure domain logic, no external dependencies)

**Cannot depend on:**
- Application layer
- Infrastructure layer
- API module

**Notes:**
- Contains domain models, domain services, domain events, and repository interfaces (Gateway pattern)
- Must remain framework-agnostic and independent
- External libraries should be minimal and only for domain-specific utilities

---

#### Infrastructure-Common Module (`{module}-infrastructure-common`)
**Can depend on:**
- `{module}-domain` (to implement domain interfaces and use domain models)

**Cannot depend on:**
- `{module}-application`
- `{module}-infrastructure-input`
- `{module}-infrastructure-output`

**Notes:**
- Contains shared infrastructure code used by both input and output adapters
- Examples: common mappers, utilities, configuration classes

---

#### Application Module (`{module}-application`)
**Can depend on:**
- `{module}-domain` (to orchestrate domain services)

**Cannot depend on:**
- `{module}-api` (API models should not leak into application layer)
- `{module}-infrastructure-common` (application must not depend on infrastructure)
- `{module}-infrastructure-input` (application shouldn't know about controllers)
- `{module}-infrastructure-output` (application shouldn't know about repositories)

**Notes:**
- Contains handlers, commands, and application services
- Orchestrates domain logic but contains no business rules
- Uses domain Gateway interfaces, not concrete implementations
- Must remain infrastructure-agnostic

---

#### Infrastructure-Input Module (`{module}-infrastructure-input`)
**Can depend on:**
- `{module}-api` (to use generated DTOs for REST endpoints)
- `{module}-domain` (to use domain models)
- `{module}-application` (to call handlers)
- `{module}-infrastructure-common` (for shared utilities)

**Cannot depend on:**
- `{module}-infrastructure-output` (input adapters shouldn't know about output adapters)

**Notes:**
- Contains REST controllers, message consumers, and other input adapters
- Maps API models to domain models
- Calls application handlers
- Returns API models to clients

---

#### Infrastructure-Output Module (`{module}-infrastructure-output`)
**Can depend on:**
- `{module}-domain` (to implement Gateway interfaces and use domain models)
- `{module}-infrastructure-common` (for shared utilities)

**Cannot depend on:**
- `{module}-api` (output adapters shouldn't know about API models)
- `{module}-application` (output adapters shouldn't know about handlers)
- `{module}-infrastructure-input` (output adapters shouldn't know about input adapters)

**Notes:**
- Contains repository implementations, external service clients, and other output adapters
- Implements domain Gateway interfaces
- Handles persistence, external API calls, and messaging

---

#### Architecture Module (`{module}-architecture`)
**Can depend on:**
- All modules within the bounded context (for testing purposes)

**Notes:**
- Contains ArchUnit tests to enforce architectural rules
- Only used at test time

---

### Dependency Flow Diagram

```
┌─────────────────────────────────────────────────────────────┐
│  API Module                                                  │
│  - OpenAPI specs                                             │
│  - Generated POJOs/interfaces                                │
│  Dependencies: NONE                                          │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ (used by)
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Infrastructure-Input Module                                 │
│  - REST Controllers                                          │
│  - Message Consumers                                         │
│  Dependencies: api, domain, application, infrastructure-common│
└─────────────────────────────┬───────────────────────────────┘
                              │
                              │ (calls)
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Application Module                                          │
│  - Handlers                                                  │
│  - Commands                                                  │
│  Dependencies: domain ONLY                                   │
└─────────────────────────────┬───────────────────────────────┘
                              │
                              │ (uses)
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Domain Module                                               │
│  - Domain Models                                             │
│  - Domain Services                                           │
│  - Gateway Interfaces                                        │
│  Dependencies: NONE                                          │
└─────────────────────────────┬───────────────────────────────┘
                              │
                              │ (implemented by)
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Infrastructure-Output Module                                │
│  - Repository Implementations                                │
│  - External Service Clients                                  │
│  Dependencies: domain, infrastructure-common                 │
└─────────────────────────────────────────────────────────────┘

                              ▲
                              │ (used by both)
                              │
┌─────────────────────────────────────────────────────────────┐
│  Infrastructure-Common Module                                │
│  - Shared utilities                                          │
│  - Common mappers                                            │
│  Dependencies: domain                                        │
└─────────────────────────────────────────────────────────────┘
```

### Cross-Module Dependencies

**Between bounded contexts:**
- Modules from different bounded contexts (e.g., `data-aggregation` and `account-verification`) should **NOT** depend on each other directly
- Communication between bounded contexts should happen via:
  - REST API calls (through infrastructure-output clients)
  - Event-driven messaging (Kafka topics)
  - Shared domain events (if applicable)

**Example violation:**
```xml
<!-- ❌ WRONG: account-verification depending on data-aggregation domain -->
<dependency>
    <groupId>com.intuit.dataexchange.banking</groupId>
    <artifactId>data-aggregation-domain</artifactId>
</dependency>
```

**Correct approach:**
```java
// ✅ CORRECT: Use REST client in infrastructure-output
@Component
public class DataAggregationClient implements DataAggregationGateway {
    // Call data-aggregation REST API
}
```

### Test Dependencies

**Test-scoped dependencies are more permissive:**
- Application module can depend on `infrastructure-output` (test scope) to use test data
- Infrastructure-input can depend on `infrastructure-output` (test scope) for integration tests
- All modules can depend on their own `test-jar` artifacts and other modules' `test-jar` artifacts

**Example:**
```xml
<!-- Application module can use infrastructure-output test utilities -->
<dependency>
    <groupId>com.intuit.dataexchange.banking</groupId>
    <artifactId>data-aggregation-infrastructure-output</artifactId>
    <type>test-jar</type>
    <scope>test</scope>
</dependency>
```




![img.png](data-aggregation.png)
