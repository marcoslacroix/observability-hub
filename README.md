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
├── k6-load-test.js                   # Script de teste de carga em JavaScript (Grafana k6)
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

## 🚀 Teste de Carga com Grafana k6

O arquivo [`k6-load-test.js`](./k6-load-test.js) utiliza o executor `ramping-arrival-rate` para disparar até **300 requisições por segundo** contra o endpoint `POST /micrometer/register-user`:
- Sobe de **50 req/s** para **200 req/s** em 20s;
- Sustenta **300 req/s** por 40s;
- Desacelera até 0 em 10s.

### Executar via Docker (com 5% de falhas simuladas para acionar métricas de erro e alerta):
```powershell
cmd /c "docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run - < k6-load-test.js"
```

### Executar com 100% de sucesso (`ERROR_RATE=0`, apenas respostas HTTP 201):
```powershell
cmd /c "docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e ERROR_RATE=0 grafana/k6 run - < k6-load-test.js"
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
