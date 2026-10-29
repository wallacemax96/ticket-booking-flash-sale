# Cielo Ticket Booking — Flash Sale Engine

Sistema distribuído de alta performance para reserva de ingressos em cenários de **Flash Sale com capacidade limitada**, projetado com foco estrito em **prevenção contra oversell (zero overselling)**, **idempotência de requisições**, **escalabilidade horizontal multi-instância** e **baixa latência sob alta concorrência**.

---

## 📑 Sumário

- [Visão Geral da Arquitetura](#-visão-geral-da-arquitetura)
- [Estratégia Anti-Oversell (Dual-Layer Defense)](#-estratégia-anti-oversell-dual-layer-defense)
- [Estrutura do Projeto e Camadas da Aplicação](#-estrutura-do-projeto-e-camadas-da-aplicação)
- [Stack Tecnológica](#-stack-tecnológica)
- [Como Executar o Projeto Localmente](#-como-executar-o-projeto-localmente)
- [Coleção de Testes e Endpoints da API](#-coleção-de-testes-e-endpoints-da-api)
- [Decisões Arquiteturais e Trade-offs](#-decisões-arquiteturais-e-trade-offs)
- [Documentação Técnica Aprofundada](#-documentação-técnica-aprofundada)

---

## 📐 Visão Geral da Arquitetura

O sistema opera de ponta a ponta em containers gerenciados pelo Docker Compose, distribuindo o tráfego via balanceamento de carga entre nós sem estado (*stateless*):

```mermaid
flowchart LR
    classDef clientBox fill:#121324,stroke:#8b5cf6,stroke-width:1.5px,color:#f8fafc;
    classDef ingressBox fill:#0f172a,stroke:#06b6d4,stroke-width:1.5px,color:#f8fafc;
    classDef appBox fill:#181829,stroke:#f59e0b,stroke-width:1.5px,color:#f8fafc;
    classDef redisBox fill:#13172c,stroke:#38bdf8,stroke-width:1.5px,color:#f8fafc;
    classDef pgBox fill:#0e1d24,stroke:#10b981,stroke-width:1.5px,color:#f8fafc;

    CLIENT["Cliente<br/>Web / Mobile"]:::clientBox
    NGINX["Nginx<br/>Load Balancer :8080"]:::ingressBox

    subgraph APP["Aplicação Spring Boot"]
        APP1["Instância 1<br/>Java 21"]:::appBox
        APP2["Instância 2<br/>Java 21"]:::appBox
    end

    REDIS[("Redis<br/>Estoque • Idempotência • Cache")]:::redisBox
    POSTGRES[("PostgreSQL<br/>Eventos e reservas")]:::pgBox

    CLIENT --> NGINX
    NGINX --> APP1
    NGINX --> APP2
    APP1 -->|"Estoque, idempotência e cache"| REDIS
    APP2 -->|"Estoque, idempotência e cache"| REDIS
    APP1 -->|"Persistência e validação final do estoque"| POSTGRES
    APP2 -->|"Persistência e validação final do estoque"| POSTGRES
```

---

## 🛡️ Estratégia Anti-Oversell (Dual-Layer Defense)

Em uma flash sale com milhares de requisições por segundo disputando cotas reduzidas, utilizar transações pesadas ou travas pessimistas (`SELECT FOR UPDATE`) no PostgreSQL causa esgotamento da pool de conexões (HikariCP starvation) e deadlocks. O sistema adota a estratégia **Dual-Layer Defense**:

### 1. Camada 1 — Fail-Fast Atômico no Redis (Memória)
* As requisições executam o script Lua atômico `reserve_tickets.lua`.
* Por rodar em thread única no Redis, o script valida e decrementa o saldo instantaneamente em memória RAM.
* **Se o saldo esgotar:** Rejeita a requisição em microssegundos com `409 Conflict (Sold Out)`, sem sequer abrir transação no banco relacional.
* **Warm-up Lazy (Recuperação de Cache):** Caso a chave do evento não exista no Redis (retorno `-1`), a aplicação busca a capacidade no PostgreSQL, aquece o Redis via `SETNX` e reexecuta o script atômico.

### 2. Camada 2 — Consistência Transacional no PostgreSQL (Disco)
* Apenas requisições aprovadas pelo Redis chegam ao banco relacional.
* A persistência utiliza um **Update Condicional Atômico**:
  * Comando SQL: `UPDATE events SET available_capacity = available_capacity - :qty WHERE id = :id AND available_capacity >= :qty;`
* O motor do PostgreSQL garante a integridade em nível de linha (*row-level*). Se por inconsistência de dados nenhuma linha for alterada (`rowsUpdated == 0`), a aplicação aciona uma rotina de compensação imediata via `release_tickets.lua` no Redis, garantindo zero desvio de saldo.

---

## 🏛️ Estrutura do Projeto e Camadas da Aplicação

O projeto adota uma arquitetura em camadas inspirada nos princípios da **Arquitetura Limpa (Clean Architecture)** e **Domain-Driven Design (DDD)**, isolando regras de negócio de detalhes de framework:

```text
src/main/java/com/cielo/ticketbooking/
├── application/
│   ├── dto/                <-- Objetos de Transferência de Dados (Records)
│   └── service/            <-- Casos de uso e orquestração de negócios
├── domain/
│   ├── exception/          <-- Exceções de domínio mapeadas para RFC 7807
│   ├── model/              <-- Entidades e Enums do Domínio (JPA)
│   └── repository/         <-- Contratos de acesso a dados (Spring Data)
├── infrastructure/
│   ├── config/             <-- Configurações de Redis, ShedLock, Async
│   └── web/                <-- Controllers REST e Handler Global de Exceções
└── TicketBookingApplication.java
```

### O que cada camada faz:

* **application/dto/ (DTOs - Data Transfer Objects):**
  * **O que são:** Estruturas imutáveis (usando Java `record`) responsáveis pelo transporte de dados entre a borda HTTP e os serviços.
  * **Exemplos:** `ReserveTicketsRequest` (validação de entrada via Bean Validation) e `ReservationResponse` (serialização de retorno sem expor entidades de banco).
* **application/service/ (Camada de Aplicação / Serviços):**
  * **O que faz:** Orquestra os fluxos de caso de uso. Não contém regras SQL diretas.
  * **Responsabilidades:** Orquestra o script Lua do Redis, gerencia a transação relacional (`@Transactional`), trata chaves de idempotência e compensação de estoque.
* **domain/model/ (Camada de Domínio / Entidades):**
  * **O que faz:** Representa o coração das regras de negócio e integridade estrutural.
  * **Componentes:** Entidades `Event` e `Reservation`, enum `ReservationStatus` (`PENDING`, `CONFIRMED`, `CANCELLED`, `EXPIRED`).
* **domain/repository/ (Repositórios):**
  * **O que faz:** Interfaces de abstração de dados (Spring Data JPA) que contêm queries otimizadas e atômicas (`@Modifying`).
* **infrastructure/web/ (Camada de Entrada / Apresentação):**
  * **O que faz:** Controllers REST que expõem os endpoints da API, realizam validação de payloads (`@Valid`) e contam com `GlobalExceptionHandler` formatando erros corporativos no padrão **RFC 7807 (Problem Details)**.
* **infrastructure/config/ (Configurações de Infraestrutura):**
  * **O que faz:** Beans de inicialização do Redis (`StringRedisTemplate`), compilação dos scripts Lua com `DefaultRedisScript`, e configuração do **ShedLock** para tarefas agendadas em nós distribuídos.

---

## 🛠️ Stack Tecnológica

* **Linguagem:** Java 21 LTS (com suporte a Virtual Threads habilitado)
* **Framework:** Spring Boot 3.3.x (Spring Web, Spring Data JPA, Spring Data Redis)
* **Banco de Dados Relacional:** PostgreSQL 16 (Engine ACID com isolamento de transação)
* **Cache & In-Memory Store:** Redis 7 (Scripts Lua atômicos e lock distribuído)
* **Migrações de Banco:** Flyway Core (Controle e versionamento de schema SQL)
* **Scheduler Distribuído:** ShedLock 5.x (com provider Redis)
* **Borda & Balanceamento:** Nginx Alpine (Load Balancing L7 via Round-Robin)
* **Padronização de Erros:** RFC 7807 Problem Details
* **Containerização:** Docker & Docker Compose

---

## 🚀 Como Executar o Projeto Localmente

### Inicialização:

1. Suba todo o ambiente de infraestrutura e aplicação com um único comando:
   ```bash
   docker compose up --build -d
   ```

2. Verifique o status dos containers:
   ```bash
   docker compose ps
   ```

3. Acompanhe os logs em tempo real:
   ```bash
   docker compose logs -f app-1 app-2
   ```

---

## 🧪 Teste de Concorrência entre Instâncias

O teste `MultiInstanceReservationIT` valida o fluxo através do Nginx e das duas instâncias reais (`app-1` e `app-2`) do Docker Compose. Ele aguarda que ambas respondam pelo balanceador, cria um evento com 5 ingressos e dispara 50 reservas simultâneas. O teste exige exatamente 5 respostas `201`, 45 respostas `409`, e confirma que as requisições foram atendidas por pelo menos dois upstreams distintos.

O Nginx inclui o header `X-Upstream-Addr` nas respostas para que o teste possa verificar quais instâncias atenderam as chamadas. Esse header é destinado à demonstração/teste local; não o exponha em um ambiente público de produção sem avaliar as implicações operacionais e de segurança.

Suba o ambiente completo, incluindo o Nginx e as duas instâncias:

```powershell
docker compose up --build -d
```

Execute o teste somente contra a infraestrutura já iniciada:

```powershell
mvn verify "-Dit.test=MultiInstanceReservationIT"
```

Para executar todos os testes unitários e de integração:

```powershell
mvn verify
```

O teste usa `http://localhost:8080` por padrão. Para apontar para outro endereço do balanceador:

```powershell
mvn verify "-Dit.test=MultiInstanceReservationIT" "-Dticket-booking.base-url=http://localhost:8080"
```

Depois do `verify`, abra `target/reports/test-report.html`. Na seção **Distribuição das requisições entre instâncias**, expanda **Ver IP do app por requisição** para ver, em cada chamada, o cliente, o status HTTP e o IP:porta do upstream Nginx que a atendeu.

---

## 📬 Coleção de Testes e Endpoints da API

O projeto inclui o arquivo **`ticket-booking-collection.json`** na raiz para importação imediata no **Postman** ou **Insomnia**.

| Método | Endpoint | Descrição | Status de Sucesso |
| :--- | :--- | :--- | :--- |
| `POST` | `/events` | Criação de evento com capacidade total de ingressos | `201 Created` |
| `GET` | `/events/:id` | Consulta capacidade e ingressos disponíveis (Cache de 5s) | `200 OK` |
| `POST` | `/events/:id/reservations` | Cria reserva (`201`) ou retorna retry idempotente (`200`) com header `Idempotency-Key` | `201 Created` / `200 OK` |
| `POST` | `/reservations/:id/confirm` | Confirma uma reserva `PENDING` ainda não expirada (endpoint demonstrativo, sem integração de pagamento) | `200 OK` |
| `GET` | `/reservations/:id` | Consulta o status detalhado de uma reserva | `200 OK` |
| `DELETE` | `/reservations/:id` | Cancela somente uma reserva `PENDING` e estorna a cota ao estoque; `CONFIRMED` retorna conflito | `204 No Content` |

---

## ⚖️ Decisões Arquiteturais e Trade-offs

* **Virtual Threads (Project Loom):** Habilitadas com `spring.threads.virtual.enabled=true`. Em vez de usar thread pools restritas pelo kernel do SO, a JVM utiliza threads leves de I/O não bloqueante, multiplicando a vazão por container em chamadas de banco e rede.
* **Idempotência em Duas Etapas:** O Redis mantém a chave `Idempotency-Key` por 15 minutos. A criação inicial retorna `201 Created`. Um retry com a mesma chave e payload retorna `200 OK` apenas enquanto a reserva permanece `PENDING`, sem criar outra reserva nem debitar estoque novamente. Tentativas com a mesma chave e payload diferente, enquanto a primeira chamada ainda está processando, ou após a reserva deixar de estar `PENDING` recebem `409 Conflict`. Se a tentativa original falhar antes do commit, a chave temporária é liberada; o resultado concluído só é gravado depois do commit da reserva.
* **ShedLock para Expiração de Reservas Pendentes:** Reservas têm validade de 10 minutos. Um job `@Scheduled` roda a cada 30 segundos limpando registros vencidos e devolvendo as cotas ao Redis e Postgres. O ShedLock garante que **apenas 1 das instâncias execute o job a cada ciclo**, eliminando concorrência entre containers.
* **Consistência Eventual na Consulta (`GET /events/:id`):** O volume de leituras em flash sales é centenas de vezes maior que o de compras. A consulta utiliza cache ultracurto no Redis (TTL de 5 segundos), absorvendo picos sem penalizar o banco relacional.

---

## 📚 Documentação Técnica Aprofundada

Para análises detalhadas com diagramas dedicados de cada subdomínio, consulte os arquivos na pasta `docs/architecture/`:

* [01. Topologia de Infraestrutura e Alta Disponibilidade](docs/architecture/01-topology.md)
* [02. Fluxo Crítico de Reserva e Barreira Anti-Oversell](docs/architecture/02-reservation-flow.md)
* [03. Ciclo de Vida da Reserva e Expiração Distribuída](docs/architecture/03-state-lifecycle.md)
* [04. Evolução Proposta: Processamento Assíncrono com Kafka](docs/architecture/04-event-driven-evolution.md)