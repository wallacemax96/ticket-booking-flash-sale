# 2. Fluxo Crítico de Reserva e Barreira Anti-Oversell

Detalhamento da máquina de decisão executada no endpoint `POST /events/:id/reservations`.

```mermaid
flowchart TD
    classDef clientBox fill:#121324,stroke:#8b5cf6,stroke-width:1.5px,color:#f8fafc;
    classDef ingressBox fill:#0f172a,stroke:#06b6d4,stroke-width:1.5px,color:#f8fafc;
    classDef appBox fill:#181829,stroke:#f59e0b,stroke-width:1.5px,color:#f8fafc;
    classDef redisBox fill:#13172c,stroke:#38bdf8,stroke-width:1.5px,color:#f8fafc;
    classDef pgBox fill:#0e1d24,stroke:#10b981,stroke-width:1.5px,color:#f8fafc;
    classDef decisionBox fill:#1e1e38,stroke:#cbd5e1,stroke-width:1.5px,color:#ffffff;
    classDef failBox fill:#1a111a,stroke:#f43f5e,stroke-dasharray: 4 4,color:#fca5a5;

    START["Cliente: POST /events/:id/reservations<br/>Header: Idempotency-Key"]:::clientBox
    NGINX["Nginx Load Balancer<br/>Porta 8080 - Round-Robin"]:::ingressBox
    API["Spring Boot - Instância N<br/>Java 21 Virtual Threads"]:::appBox

    START --> NGINX
    NGINX --> API

    subgraph S1 ["1. Controle de Idempotência"]
        API --> IDEM_CHECK{"SETNX idempotency:key<br/>TTL 15m no Redis"}:::decisionBox
        IDEM_CHECK -->|Mesma chave e payload; reserva PENDING| RET_CACHE["Retorna a reserva existente<br/>200 OK<br/>Sem novo débito ou INSERT"]:::pgBox
        IDEM_CHECK -->|Status diferente de PENDING| RET_CLOSED["Retorna 409 Conflict<br/>Reserva encerrada ou expirada"]:::failBox
        IDEM_CHECK -->|Em processamento ou payload diferente| RET_CONF["Retorna 409 Conflict<br/>Chave em uso ou payload diferente"]:::failBox
        IDEM_CHECK -->|Chave nova adquirida| S2_ENTRY["Prossegue para validação de cota"]:::appBox
    end

    subgraph S2 ["2. Barreira Atômica Anti-Oversell"]
        S2_ENTRY --> LUA["Executa reserve_tickets.lua<br/>Redis Single-Thread"]:::redisBox
        LUA --> LUA_CHECK{"Resultado Script Lua"}:::decisionBox
        LUA_CHECK -->|Retorno -1: Chave ausente| WARMUP["Busca capacidade no PostgreSQL<br/>e aquece Redis com SETNX"]:::pgBox
        WARMUP --> RETRY_LUA["Reexecuta reserve_tickets.lua"]:::redisBox
        RETRY_LUA --> RETRY_CHECK{"Resultado após warm-up"}:::decisionBox
        RETRY_CHECK -->|Retorno -1: Evento não encontrado| RET_404["Retorna 404 Not Found"]:::failBox
        RETRY_CHECK -->|Retorno 0: Saldo esgotado| RET_409["Retorna 409 Conflict<br/>Sold Out"]:::failBox
        RETRY_CHECK -->|Retorno 1: Cota debitada| S3_ENTRY["Prossegue para persistência relacional"]:::appBox
        LUA_CHECK -->|Retorno 0: Saldo esgotado| RET_409
        LUA_CHECK -->|Retorno 1: Cota debitada| S3_ENTRY
    end

    subgraph S3 ["3. Persistência e Consistência Transacional"]
        S3_ENTRY --> SQL_UPDATE["UPDATE events SET available = available - qty<br/>WHERE id = id AND available >= qty"]:::pgBox
        SQL_UPDATE --> DB_CHECK{"Linhas afetadas?"}:::decisionBox

        DB_CHECK -->|0: Saldo divergente| ROLLBACK["EVAL release_tickets.lua<br/>Estorno da cota no Redis"]:::redisBox
        ROLLBACK --> RET_DB_ERR["Retorna 409 Conflict<br/>Saldo insuficiente no banco"]:::failBox

        DB_CHECK -->|1 linha afetada| SQL_INSERT["INSERT INTO reservations<br/>Status: PENDING, expira em 10 min"]:::pgBox
        SQL_INSERT --> REDIS_FIN["Após commit, salva a resposta<br/>na chave de idempotência"]:::redisBox
        REDIS_FIN --> SUCCESS["Retorna 201 Created<br/>Nova reserva PENDING"]:::pgBox
    end

    style S1 fill:#090a14,stroke:#8b5cf6,stroke-width:1px,color:#c4b5fd;
    style S2 fill:#080c18,stroke:#38bdf8,stroke-width:1px,color:#7dd3fc;
    style S3 fill:#061314,stroke:#10b981,stroke-width:1px,color:#6ee7b7;
```
