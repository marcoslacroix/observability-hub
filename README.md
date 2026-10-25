# Observability Hub — Spring Boot 4 + LGTM Stack + Pyroscope + Jira + k6

Plataforma centralizada de **Observabilidade, Métricas de Negócio, Alertas e Engenharia** em **Spring Boot 4.1.1 (Java 21)**, integrando:

- **Traces Distribuídos**: OpenTelemetry + **Grafana Tempo**
- **Logs Estruturados (correlacionados por `traceId` e `spanId`)**: OpenTelemetry + **Grafana Loki**
- **Métricas Técnicas (JVM/HTTP) & de Negócio (`Counter` / `Timer`)**: Micrometer + **Prometheus**
- **Continuous Profiling (CPU & Memory Allocation)**: **Grafana Pyroscope**
- **Alertas Automatizados por E-mail (Taxa de Erros HTTP 5xx > 2%)**: Grafana Unified Alerting + **Mailpit (SMTP)**
- **Health Check Customizado de Dependências Externas**: Spring Boot Actuator (`HealthIndicator`)
- **Testes de Carga Automatizados**: **Grafana k6**
- **Gestão Ágil & Produtividade**: Integração com a API v3 do **Jira Cloud** via Grafana Infinity Datasource

---

## 📂 Estrutura do Projeto

```text
observability-hub/
├── Dockerfile
├── docker-compose.yml
├── .env
├── otel-collector-config.yaml
├── tempo.yaml
├── loki.yaml
├── prometheus.yml
├── requests.http                     # Requisições prontas para executar direto no IntelliJ IDEA
├── load-tests/
│   ├── k6/
│   │   ├── smoke-test.js             # Teste rápido de fumaça (validação básica)
│   │   ├── load-test.js              # Teste de carga com ramp-up gradual de RPS
│   │   ├── stress-test.js            # Teste de estresse para encontrar limites de falha
│   │   └── alloc-test.js             # Carga de alocação de memória (profiling/GC)
│   ├── scenarios/                    # Cenários reutilizáveis por domínio (importados pelos testes acima)
│   │   ├── common.js                 # BASE_URL e helpers compartilhados
│   │   ├── kafka.js                  # Publicação/consumo Kafka + resumo de lag no Prometheus
│   │   ├── users.js                  # Registro de utilizador
│   │   └── alloc.js                  # Alocação de memória (profiling)
│   └── thresholds.js                 # Definição de SLOs e falhas automáticas (ex: p95 < 200ms)
├── grafana/
│   └── provisioning/
│       ├── datasources/
│       │   └── datasources.yml       # Prometheus, Loki, Tempo, Pyroscope e Jira
│       ├── alerting/
│       │   └── alerts.yml            # Alerta de taxa 5xx > 2% (5m) + Contact Point de E-mail
│       └── dashboards/
│           ├── dashboards.yml
│           ├── grafana-tempo-dashboard.json
│           ├── jira-dashboard.json
│           ├── kafka-observability-dashboard.json
│           ├── micrometer-business-metrics.json
│           ├── pyroscope-overview.json
│           └── spring-boot-prometheus.json
├── pom.xml
└── src/
    └── main/
        ├── java/com/example/observability/
        │   ├── ObservabilityHubApplication.java
        │   ├── OpenTelemetryLogbackConfig.java
        │   ├── CustomServiceHealthIndicator.java
        │   ├── controller/
        │   │   ├── AllocationController.java
        │   │   └── MicrometerController.java
        │   ├── dto/
        │   │   ├── TaxResult.java
        │   │   └── UserDto.java
        │   └── service/
        │       └── MicrometerService.java
        └── resources/
            └── application.yaml
```

---

## ⚙️ Como Compilar e Subir o Ambiente

### 1. Compilar e gerar o pacote `.jar` da aplicação
```powershell
.\mvnw.cmd clean package -DskipTests
```
O artefato empacotado será gerado em `target/observability-hub-0.0.1-SNAPSHOT.jar`.

### 2. Subir toda a stack com Docker Compose
```powershell
docker compose up -d --build
```

> **Dica (Desenvolvimento Local no IntelliJ IDEA)**: Se preferir rodar a aplicação Spring Boot diretamente pelo IntelliJ IDEA na porta `8080`, suba apenas os containers de infraestrutura:
> ```powershell
> docker compose up -d otel-collector loki prometheus mailpit grafana tempo pyroscope
> ```

### 🌐 Portas e Acessos dos Serviços
| Serviço | URL | Credenciais / Observações |
| :--- | :--- | :--- |
| **Spring Boot App** | `http://localhost:8080` | API REST + Actuator (`/actuator/health`, `/actuator/prometheus`) |
| **Grafana** | `http://localhost:3000` | Usuário: `admin` / Senha: `admin` |
| **Mailpit (Webmail de Alertas)** | `http://localhost:8025` | Caixa de entrada local que recebe os e-mails de alerta do Grafana |
| **Prometheus** | `http://localhost:9090` | Coleta métricas em `/actuator/prometheus` a cada 5s |
| **Pyroscope** | `http://localhost:4040` | Continuous Profiling (Flamegraphs de memória e CPU) |
| **Tempo** | `http://localhost:3200` | Backend de Traces Distribuídos |
| **Loki** | `http://localhost:3100` | Agregador de Logs correlacionados por `traceId` |
| **OpenTelemetry Collector** | `localhost:4317` (gRPC) / `4318` (HTTP) | Recebe telemetria OTLP e encaminha ao Tempo e Loki |

---

## 🧪 Endpoints da Aplicação

Você pode disparar todas as requisições diretamente pelo **IntelliJ IDEA** abrindo o arquivo [`requests.http`](./requests.http) e clicando no ícone **▶️ (Run)** ao lado de cada chamada, ou via `curl`:

### 1. Métricas de Negócio com Micrometer (`/micrometer`)
- **Registar Utilizador com Sucesso** (`Counter` `user_registrations_total{status="success"}`):
  ```bash
  curl -X POST "http://localhost:8080/micrometer/register-user" \
    -H "Content-Type: application/json" \
    -d '{"name": "Marcos Lacroix", "email": "marcos@example.com"}'
  ```
- **Registar Utilizador Inválido — Simular Falha** (`Counter` `user_registrations_total{status="failure"}` + HTTP 500):
  ```bash
  curl -X POST "http://localhost:8080/micrometer/register-user" \
    -H "Content-Type: application/json" \
    -d '{"name": "", "email": ""}'
  ```
- **Cálculo Pesado de Impostos** (`Timer` `business_tax_calculation_time_seconds{region="BR"}` — duração aleatória de 1s a 5s):
  ```bash
  curl "http://localhost:8080/micrometer/heavy-calculations"
  ```
- **Simular Erro HTTP 500** (para testar o disparo do alerta de taxa de erros 5xx > 2%):
  ```bash
  curl -i "http://localhost:8080/micrometer/simulate-5xx"
  ```

### 2. Alocação de Memória & Profiling (`/alloc`)
- **Alocação de blocos de bytes (`byte[]` de 1 MB)**:
  ```bash
  curl "http://localhost:8080/alloc/bytes?mb=20"
  ```
- **Alocação de objetos em `HashMap` (pressão no Garbage Collector)**:
  ```bash
  curl "http://localhost:8080/alloc/objects?items=200000"
  ```

### 3. Actuator & Health Check Customizado (`/actuator`)
- **Health Check Geral e de Dependência Externa (`customServiceHealth`)**:
  ```bash
  curl "http://localhost:8080/actuator/health"
  ```
  *(Executa o `CustomServiceHealthIndicator`, validando conectividade com API externa e exibindo os detalhes no JSON).*
- **Métricas no formato Prometheus**:
  ```bash
  curl "http://localhost:8080/actuator/prometheus"
  ```

---

## 🚀 Testes de Performance com Grafana k6 (`load-tests/`)

Os testes do **Grafana k6** estão organizados em [`load-tests/`](./load-tests), compartilhando as definições de SLOs em [`load-tests/thresholds.js`](./load-tests/thresholds.js):

### 1. Smoke Test ([`load-tests/k6/smoke-test.js`](./load-tests/k6/smoke-test.js))
Validação rápida (2 VUs por 15s, SLO `p95 < 200ms` e 0% de falhas) cobrindo `/actuator/health`, `/micrometer/register-user`, `/kafka/publish`, `/kafka/consumed` e `/alloc/bytes`:
```powershell
docker run --rm -v "${PWD}/load-tests:/load-tests" -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run /load-tests/k6/smoke-test.js
```

### 2. Load Test ([`load-tests/k6/load-test.js`](./load-tests/k6/load-test.js))
Utiliza o executor `ramping-arrival-rate` para disparar até **300 requisições por segundo** contra o endpoint `POST /micrometer/register-user`:
- Sobe de **50 req/s** para **200 req/s** em 20s;
- Sustenta **300 req/s** por 40s;
- Desacelera até 0 em 10s.

#### Executar via Docker (com 5% de falhas simuladas):
```powershell
docker run --rm -v "${PWD}/load-tests:/load-tests" -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run /load-tests/k6/load-test.js
```

#### Executar com 100% de sucesso (`ERROR_RATE=0`, apenas respostas HTTP 201):
```powershell
docker run --rm -v "${PWD}/load-tests:/load-tests" -e BASE_URL=http://host.docker.internal:8080 -e ERROR_RATE=0 grafana/k6 run /load-tests/k6/load-test.js
```

---

## 🔥 Stress Test do Apache Kafka ([`load-tests/k6/stress-test.js`](./load-tests/k6/stress-test.js))

O arquivo [`load-tests/k6/stress-test.js`](./load-tests/k6/stress-test.js) executa **3 cenários simultâneos** e exibe ao final um resumo comparativo das métricas `kafka_messages_published_total` vs `kafka_messages_consumed_total` coletadas do `/actuator/prometheus`:

1. **`stress_kafka_publish`** (`POST /kafka/publish` → tópico `observability-events-topic`);
2. **`stress_user_registration_kafka`** (`POST /micrometer/register-user` → tópico `user-registrations-topic`);
3. **`poll_kafka_consumed`** (`GET /kafka/consumed`): realiza leituras concorrentes das listas de eventos processados pelos `@KafkaListener`.

### Executar o teste de stress (direto na rede interna do Docker Compose):
```powershell
docker run --rm --network observability-hub_default -v "${PWD}/load-tests:/load-tests" -e BASE_URL=http://observability-hub:8080 grafana/k6 run /load-tests/k6/stress-test.js
```

### Executar o teste de stress simulando 5% de falhas no registo de utilizador (`ERROR_RATE=0.05`):
```powershell
docker run --rm --network observability-hub_default -v "${PWD}/load-tests:/load-tests" -e BASE_URL=http://observability-hub:8080 -e ERROR_RATE=0.05 grafana/k6 run /load-tests/k6/stress-test.js
```

---

## 🚨 Alertas Automatizados por E-mail no Grafana

O arquivo [`grafana/provisioning/alerting/alerts.yml`](./grafana/provisioning/alerting/alerts.yml) provisiona automaticamente:
- **Regra**: `Taxa de Erros HTTP 5xx > 2% (Janela de 5 minutos)` (pasta **Alertas Spring Boot**).
- **Fórmula PromQL**: Calcula a razão entre requisições com `status=~"5.."` e o total de requisições da aplicação nos últimos 5 minutos (`[5m]`). Quando ultrapassa **2%**, o alerta entra em estado `Firing`.
- **Notificação por E-mail (`email-alertas`)**:
  - Por padrão, envia para o **Mailpit** local (`http://localhost:8025`), permitindo visualizar os e-mails de alerta imediatamente sem configurar credenciais externas.
  - Para receber em um e-mail externo real (Gmail, Office 365 ou SMTP corporativo), edite as variáveis `ALERT_EMAIL_TO`, `SMTP_HOST`, `SMTP_USER` e `SMTP_PASSWORD` no arquivo [`.env`](./.env) e recrie o container do Grafana (`docker compose up -d grafana`).

---

## 📊 Dashboards Provisionados no Grafana (`http://localhost:3000`)

1. **Micrometer - Métricas de Negócio (Utilizadores & Impostos)** (`/d/micrometer-business-metrics`)
   - Contadores de sucesso/falha no registo de utilizadores (`user_registrations_total`), taxa de sucesso (%), gráfico de proporção (Donut), taxa de registos/s e estatísticas de duração média/máxima do cálculo de impostos (`business_tax_calculation_time_seconds`).
2. **Spring Boot - JVM & HTTP (Prometheus)** (`/d/spring-boot-prometheus`)
   - Uptime, uso de Heap/Non-Heap, pausas de GC, CPU, Threads, taxa de requisições por URI e percentual de erros 5xx.
3. **OpenTelemetry & Tempo - Traces e Logs (Trace ID)** (`/d/tempo-trace-dashboard`)
   - Busca de traces distribuídos no Tempo correlacionados diretamente aos logs estruturados no Loki via `traceId`.
4. **Pyroscope - Profiling** (`/d/pyroscope-overview`)
   - Flamegraphs de alocação de memória e CPU gerados pelo agente Java do Pyroscope.
5. **Jira - Issues, Sprints, Velocidade & Lead Time** (`/d/jira-overview-dashboard`)
   - Métricas de engenharia e produtividade integradas à API v3 do Jira Cloud.
6. **Apache Kafka - Stress Test, Vazão & Tempo de Processamento** (`/d/kafka-stress-metrics`)
   - Contadores de sucesso/falha de publicação e consumo (`kafka_messages_published_total` e `kafka_messages_consumed_total`), Consumer Lag residual, Timers de duração do Producer ACK (`kafka_producer_processing_time_seconds`) e do `@KafkaListener` (`kafka_consumer_processing_time_seconds`), além da latência e taxa das requisições HTTP do teste de stress do k6.
