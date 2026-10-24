# Observability Hub — Spring Boot 4 + LGTM Stack + Pyroscope + Jira

Plataforma centralizada de **Observabilidade e Engenharia** em Spring Boot 4 (Java 21), integrando:

- **Traces Distribuídos**: OpenTelemetry + **Grafana Tempo**
- **Logs Estruturados (correlacionados por `traceId`)**: OpenTelemetry + **Grafana Loki**
- **Métricas da JVM & HTTP**: Micrometer + **Prometheus**
- **Continuous Profiling (CPU & Memory Allocation)**: **Grafana Pyroscope**
- **Gestão Ágil & Produtividade**: Integração com a API v3 do **Jira Cloud** via Grafana Infinity Datasource

---

## 📂 Estrutura do Projeto

```text
observability-hub/
├── Dockerfile
├── docker-compose.yml
├── otel-collector-config.yaml
├── tempo.yaml
├── loki.yaml
├── prometheus.yml
├── grafana/
│   └── provisioning/
│       ├── datasources/datasources.yml
│       └── dashboards/
│           ├── grafana-tempo-dashboard.json
│           ├── jira-dashboard.json
│           ├── pyroscope-overview.json
│           └── spring-boot-prometheus.json
├── pom.xml
└── src/
    └── main/
        ├── java/com/example/observability/
        │   ├── ObservabilityHubApplication.java
        │   ├── AllocationController.java
        │   └── OpenTelemetryLogbackConfig.java
        └── resources/
            └── application.yaml
```

---

## ⚙️ Como Compilar e Executar

### 1. Gerar o pacote `.jar` da aplicação
```bash
./mvnw clean package -DskipTests
```
O artefato será gerado em `target/observability-hub-0.0.1-SNAPSHOT.jar`.

### 2. Subir toda a stack com Docker Compose
```bash
docker compose up -d --build
```

---

## 🧪 Endpoints de Simulação (Logs, Traces, Métricas e Profiling)

- **Alocação de blocos de bytes (`byte[]` de 1 MB)**:
  ```bash
  curl "http://localhost:8080/alloc/bytes?mb=20"
  ```
- **Alocação de objetos em `HashMap` (pressão no GC)**:
  ```bash
  curl "http://localhost:8080/alloc/objects?items=200000"
  ```

---

## 📊 Dashboards Provisionados no Grafana (`http://localhost:3000`)

1. **OpenTelemetry & Tempo - Traces e Logs (Trace ID)** (`tempo-trace-dashboard`)
2. **Jira - Issues, Sprints, Velocidade & Lead Time** (`jira-overview-dashboard`)
3. **Pyroscope - Profiling** (`pyroscope-overview`)
4. **Spring Boot Prometheus Metrics** (`spring-boot-prometheus`)
