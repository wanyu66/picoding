# Diagram patterns

Use diagrams only when they materially clarify relationships. Labels must describe verified components and edges.

## Component flow

```mermaid
flowchart LR
    A["Entry point"] --> B["Application service"]
    B --> C["Data or external boundary"]
```

## Request sequence

```mermaid
sequenceDiagram
    participant Client
    participant Endpoint
    participant Service
    participant Store
    Client->>Endpoint: Request
    Endpoint->>Service: Validated input
    Service->>Store: Read or write
    Store-->>Service: Result
    Service-->>Endpoint: Response model
    Endpoint-->>Client: Response
```

## Module dependencies

```mermaid
flowchart TD
    UI["Presentation"] --> API["Application boundary"]
    API --> Core["Core logic"]
    Core --> Infra["Infrastructure adapters"]
```

Do not put secrets, personal data, inferred network addresses, or repository-external paths in diagrams. Mark uncertain edges in accompanying prose instead of presenting them as fact.
