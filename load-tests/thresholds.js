// Definição centralizada de SLOs (Service Level Objectives) e falhas automáticas para o Grafana k6.
// Permite sobrescrever o limite de p95 via variável de ambiente (ex: -e SLO_P95_MS=200).
const SLO_P95_MS = parseInt(__ENV.SLO_P95_MS || '200', 10);
const LOAD_SLO_P95_MS = parseInt(__ENV.LOAD_SLO_P95_MS || '500', 10);

// Thresholds padrão (SLO base: p95 < 200ms e taxa de erro < 1%)
export const defaultThresholds = {
  http_req_duration: [`p(95)<${SLO_P95_MS}`, 'p(99)<500'],
  http_req_failed: ['rate<0.01'],
  checks: ['rate>0.99'],
};

// Thresholds para Smoke Test (validação rápida: zero falhas e latência estrita p95 < 200ms)
export const smokeThresholds = {
  http_req_duration: [`p(95)<${SLO_P95_MS}`, 'p(99)<400'],
  http_req_failed: ['rate==0'],
  checks: ['rate==1.0'],
};

// Thresholds para Load Test (carga sustentada com ramp-up de RPS)
export const loadThresholds = {
  http_req_duration: [`p(90)<${SLO_P95_MS}`, `p(95)<${LOAD_SLO_P95_MS}`],
  checks: ['rate>0.95'],
};

// Thresholds para Alloc Test (requisições pesadas: alocam MBs na JVM, então a latência tolerada é maior)
export const allocThresholds = {
  'http_req_duration{scenario:alloc_bytes}': ['p(95)<2000'],
  'http_req_duration{scenario:alloc_objects}': ['p(95)<2000'],
  http_req_failed: ['rate<0.01'],
  checks: ['rate>0.99'],
};

// Thresholds para Stress Test (limites sob estresse concorrente de HTTP + Apache Kafka + agentes OTel/Pyroscope)
export const stressThresholds = {
  'http_req_duration{scenario:stress_kafka_publish}': ['p(90)<800', 'p(95)<2000'],
  'http_req_duration{scenario:stress_user_registration_kafka}': ['p(90)<800', 'p(95)<2000'],
  'http_req_duration{scenario:poll_kafka_consumed}': ['p(90)<800', 'p(95)<2000'],
  kafka_publish_success_rate: ['rate>0.98'],
  kafka_consumed_success_rate: ['rate>0.95'],
};
