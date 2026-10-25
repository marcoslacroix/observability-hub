# Observability Hub — Spring Boot 4 + LGTM Stack + Pyroscope + Kafka + Jira + k6

Plataforma centralizada de **Observabilidade, Métricas de Negócio, Alertas e Engenharia** em **Spring Boot 4.1.1 (Java 21)**, integrando:

- **Traces Distribuídos**: OpenTelemetry + **Grafana Tempo**
- **Logs Estruturados (correlacionados por `traceId` e `spanId`)**: OpenTelemetry + **Grafana Loki**
- **Métricas Técnicas (JVM/HTTP) & de Negócio (`Counter` / `Timer`)**: Micrometer + **Prometheus**
- **Continuous Profiling (CPU & Memory Allocation / Leaks)**: **Grafana Pyroscope**
- **Alertas Automatizados por E-mail (Erros 5xx e Heap > 85%)**: Grafana Unified Alerting + **Mailpit (SMTP)**
- **Mensageria com Observabilidade Total**: **Apache Kafka** (KRaft) + **Kafka UI**
- **Health Check Customizado de Dependências Externas**: Spring Boot Actuator (`HealthIndicator`)
- **Testes de Carga & Stress Automatizados**: **Grafana k6**
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
│   │   ├── kafka-stress-test.js      # Teste de estresse para encontrar limites de falha
│   │   └── alloc-test.js             # Carga de alocação de memória (profiling/GC)
│   ├── scenarios/                    # Cenários reutilizáveis por domínio
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
│       │   └── alerts.yml            # Regras (5xx > 2%, Heap > 85%) + Contact Points de E-mail
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
        │   ├── config/
        │   │   └── KafkaConfig.java
        │   ├── controller/
        │   │   ├── AllocationController.java
        │   │   ├── KafkaController.java
        │   │   └── MicrometerController.java
        │   ├── dto/
        │   │   ├── KafkaMessageRequest.java
        │   │   ├── TaxResult.java
        │   │   ├── UserDto.java
        │   │   └── UserRegisteredEvent.java
        │   ├── exception/
        │   │   ├── BusinessValidationException.java
        │   │   └── GlobalExceptionHandler.java
        │   └── service/
        │       ├── KafkaConsumerService.java
        │       ├── KafkaProducerService.java
        │       ├── LeakService.java
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
> docker compose up -d otel-collector loki prometheus mailpit grafana tempo pyroscope kafka kafka-ui
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
| **Apache Kafka** | `localhost:9092` (externo) / `kafka:29092` | Broker KRaft (sem Zookeeper) |
| **Kafka UI** | `http://localhost:8085` | Gerenciador web de tópicos, mensagens e consumer groups |

---

## 🧪 Endpoints da Aplicação

Você pode disparar todas as requisições diretamente pelo **IntelliJ IDEA** abrindo o arquivo [`requests.http`](./requests.http) e clicando no ícone **▶️ (Run)** ao lado de cada chamada, ou via `curl` / PowerShell:

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

### 2. Mensageria Apache Kafka (`/kafka`)
- **Publicar Mensagem Assíncrona no Tópico**:
  ```bash
  curl -X POST "http://localhost:8080/kafka/publish" \
    -H "Content-Type: application/json" \
    -d '{"message": "Evento de teste de observabilidade", "tag": "batch-1"}'
  ```
- **Consultar Mensagens Consumidas pelos Listeners**:
  ```bash
  curl "http://localhost:8080/kafka/consumed"
  ```

### 3. Alocação de Memória, Memory Leak & Profiling (`/alloc`)
- **Alocação de blocos de bytes transitórios (`byte[]` de 1 MB)**:
  ```bash
  curl "http://localhost:8080/alloc/bytes?mb=20"
  ```
- **Alocação de objetos em `HashMap` (pressão no Garbage Collector)**:
  ```bash
  curl "http://localhost:8080/alloc/objects?items=200000"
  ```
- **Simular Memory Leak (Retenção proposital de objetos em Cache Estático)**:
  ```bash
  # Retém 1000 entradas (~10 MB retidos na JVM com arrays de 10 KB)
  curl "http://localhost:8080/alloc/leak?entries=1000"
  ```
- **Limpar Memória Retida pelo Leak**:
  ```bash
  curl "http://localhost:8080/alloc/leak/clear"
  ```

### 4. Actuator & Health Check Customizado (`/actuator`)
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

## 🔍 Continuous Profiling com Pyroscope & Análise de Memory Leaks

A aplicação é inicializada com o agente Java do Pyroscope (`-javaagent:/app/pyroscope.jar`), enviando perfis contínuos de CPU, TLAB e alocação de objetos para o container `pyroscope:4040`.

### Isolando a linha de código exata do Memory Leak no Flame Graph
1. Execute chamadas no endpoint de leak para gerar retenção de objetos:
   ```powershell
   1..10 | % { Invoke-RestMethod "http://localhost:8080/alloc/leak?entries=2000" }
   ```
2. Acesse o Grafana em `http://localhost:3000` > **Explore** (ou no dashboard `Pyroscope - Profiling`).
3. Selecione o datasource **Pyroscope** e defina:
   - **Profile type**: `memory:alloc_in_new_tlab_bytes:bytes:space:bytes`
   - **Query**: `{service_name="observability-hub"}`
4. No **Flame Graph / Table View**, observe o frame com maior **Self** e **Total**:
   - `com/example/observability/service/LeakService.buildEntry`
   - Esse nó corresponde à linha exata de alocação: `byte[] payload = new byte[10 * 1024];`, originada de `LeakService.leak` e chamada por `AllocationController.leak`.

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

### 3. Allocation & GC Pressure Test ([`load-tests/k6/alloc-test.js`](./load-tests/k6/alloc-test.js))
Estressa o coletor de lixo da JVM disparando chamadas aos endpoints de alocação de byte arrays e HashMaps:
```powershell
docker run --rm -v "${PWD}/load-tests:/load-tests" -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run /load-tests/k6/alloc-test.js
```

---

## 🔥 Stress Test do Apache Kafka ([`load-tests/k6/kafka-stress-test.js`](./load-tests/k6/kafka-stress-test.js))

O arquivo [`load-tests/k6/kafka-stress-test.js`](./load-tests/k6/kafka-stress-test.js) executa **3 cenários simultâneos** e exibe ao final um resumo comparativo das métricas `kafka_messages_published_total` vs `kafka_messages_consumed_total` coletadas do `/actuator/prometheus`:

1. **`stress_kafka_publish`** (`POST /kafka/publish` → tópico `observability-events-topic`);
2. **`stress_user_registration_kafka`** (`POST /micrometer/register-user` → tópico `user-registrations-topic`);
3. **`poll_kafka_consumed`** (`GET /kafka/consumed`): realiza leituras concorrentes das listas de eventos processados pelos `@KafkaListener`.

### Executar o teste de stress (direto na rede interna do Docker Compose):
```powershell
docker run --rm --network observability-hub_default -v "${PWD}/load-tests:/load-tests" -e BASE_URL=http://observability-hub:8080 grafana/k6 run /load-tests/k6/kafka-stress-test.js
```

---

## 🚨 Alertas Automatizados por E-mail no Grafana

O arquivo [`grafana/provisioning/alerting/alerts.yml`](./grafana/provisioning/alerting/alerts.yml) provisiona automaticamente regras de alerta e canais de notificação:

### Regras de Alerta Ativas
1. **Taxa de Erros HTTP 5xx > 2% (Janela de 5 minutos)** (`http_5xx_rate_2pct_5m`)
   - **Severidade**: `critical`
   - **Condição**: Dispara se a razão de erros 5xx superar 2% por mais de 1 minuto.
   - **Canal de Notificação**: `email-alertas`

2. **Heap da JVM > 85%** (`jvm_heap_above_85pct`)
   - **Severidade**: `warning`
   - **Fórmula PromQL**:
     ```promql
     sum(jvm_memory_used_bytes{application="observability-hub", area="heap"})
     /
     sum(jvm_memory_max_bytes{application="observability-hub", area="heap"} > 0) * 100
     ```
   - **Condição**: Dispara se o uso de Heap ultrapassar 85% do limite `-Xmx` por mais de 2 minutos contínuos.
   - **Canal de Notificação**: `email-heap` com assunto direcionado e orientações para inspecionar no Pyroscope.

### Configuração do Envio de E-mails
- Por padrão, os alertas são roteados via SMTP para o **Mailpit** local (`http://localhost:8025`).
- Para receber em caixa externa corporativa ou pessoal, configure as variáveis `ALERT_EMAIL_TO`, `SMTP_HOST`, `SMTP_USER` e `SMTP_PASSWORD` no arquivo [`.env`](./.env) e reinicie o Grafana (`docker restart grafana`).

---

## 📊 Dashboards Provisionados no Grafana (`http://localhost:3000`)

1. **Micrometer - Métricas de Negócio (Utilizadores & Impostos)** (`/d/micrometer-business-metrics`)
   - Contadores de sucesso/falha no registo de utilizadores (`user_registrations_total`), taxa de sucesso (%), gráfico de proporção (Donut), taxa de registos/s e estatísticas de duração média/máxima do cálculo de impostos (`business_tax_calculation_time_seconds`).
2. **Spring Boot - JVM & HTTP (Prometheus)** (`/d/spring-boot-prometheus`)
   - Uptime, uso de Heap/Non-Heap, pausas de GC, CPU, Threads, taxa de requisições por URI e percentual de erros 5xx.
3. **OpenTelemetry & Tempo - Traces e Logs (Trace ID)** (`/d/tempo-trace-dashboard`)
   - Busca de traces distribuídos no Tempo correlacionados diretamente aos logs estruturados no Loki via `traceId`.
4. **Pyroscope - Profiling** (`/d/pyroscope-overview`)
   - Flame graphs em tempo real de alocação de memória (TLAB bytes/objetos) e CPU gerados pelo agente Java do Pyroscope.
5. **Apache Kafka - Stress Test, Vazão & Tempo de Processamento** (`/d/kafka-stress-metrics`)
   - Contadores de publicação e consumo (`kafka_messages_published_total` e `kafka_messages_consumed_total`), Consumer Lag residual, Timers de latência do Producer e do Consumer.
6. **Jira - Issues, Sprints, Velocidade & Lead Time** (`/d/jira-overview-dashboard`)
   - Métricas de engenharia e produtividade integradas à API v3 do Jira Cloud via Infinity Datasource.
