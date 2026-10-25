import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// URL base (usa host.docker.internal se rodar via container Docker do k6, ou localhost se rodar localmente)
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Percentual de falhas simuladas no endpoint /micrometer/register-user (padrão: 0 para focar 100% no stress do Kafka; use ex: 0.05 para 5% de erro)
const ERROR_RATE = parseFloat(__ENV.ERROR_RATE || '0');

// Taxas máximas de pico calibradas para rodar com estabilidade no Docker Desktop local (11 containers ativos)
// Podem ser sobrescritas via -e MAX_PUBLISH_RATE=200 -e MAX_REGISTER_RATE=100
const MAX_PUBLISH_RATE = parseInt(__ENV.MAX_PUBLISH_RATE || '80', 10);
const MAX_REGISTER_RATE = parseInt(__ENV.MAX_REGISTER_RATE || '40', 10);

// Métricas customizadas do k6 para observabilidade por fluxo
const kafkaPublishDuration = new Trend('kafka_publish_req_duration', true);
const userRegisterDuration = new Trend('kafka_user_register_req_duration', true);
const kafkaConsumedDuration = new Trend('kafka_consumed_req_duration', true);

const kafkaMessagesSent = new Counter('k6_kafka_messages_triggered_total');
const kafkaPublishSuccessRate = new Rate('kafka_publish_success_rate');
const kafkaConsumedSuccessRate = new Rate('kafka_consumed_success_rate');

export const options = {
  scenarios: {
    // 1. Stress de publicação direta no tópico customizado (POST /kafka/publish)
    stress_kafka_publish: {
      executor: 'ramping-arrival-rate',
      exec: 'publishCustomKafkaEvent',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 20,
      maxVUs: 100,
      stages: [
        { target: Math.round(MAX_PUBLISH_RATE * 0.5), duration: '15s' }, // Aquecimento gradual
        { target: MAX_PUBLISH_RATE, duration: '30s' },                   // Carga alta sustentada
        { target: 0, duration: '10s' },                                  // Ramp-down (cooldown)
      ],
    },

    // 2. Stress do fluxo de negócio + evento Kafka (POST /micrometer/register-user)
    stress_user_registration_kafka: {
      executor: 'ramping-arrival-rate',
      exec: 'registerUserAndPublishEvent',
      startRate: 5,
      timeUnit: '1s',
      preAllocatedVUs: 15,
      maxVUs: 80,
      stages: [
        { target: Math.round(MAX_REGISTER_RATE * 0.5), duration: '15s' }, // Sobe até metade do pico
        { target: MAX_REGISTER_RATE, duration: '30s' },                   // Sustenta pico
        { target: 0, duration: '10s' },                                   // Ramp-down
      ],
    },

    // 3. Leitura concorrente das mensagens consumidas pelos @KafkaListeners (GET /kafka/consumed)
    poll_kafka_consumed: {
      executor: 'constant-arrival-rate',
      exec: 'pollConsumedKafkaEvents',
      rate: 2,               // 2 consultas/s concorrentes enquanto o broker é estressado
      timeUnit: '1s',
      duration: '55s',
      preAllocatedVUs: 2,
      maxVUs: 10,
    },
  },

  thresholds: {
    // SLAs de latência por cenário (considerando overhead dos agentes OpenTelemetry + Pyroscope ativos no container)
    'http_req_duration{scenario:stress_kafka_publish}': ['p(90)<500', 'p(95)<2000'],
    'http_req_duration{scenario:stress_user_registration_kafka}': ['p(90)<500', 'p(95)<2000'],
    'http_req_duration{scenario:poll_kafka_consumed}': ['p(90)<500', 'p(95)<2000'],
    // Taxas mínimas de sucesso
    kafka_publish_success_rate: ['rate>0.98'],
    kafka_consumed_success_rate: ['rate>0.95'],
  },
};

// Extrai o valor de uma métrica do endpoint /actuator/prometheus
function extractPrometheusMetric(body, metricName) {
  if (!body) return 0;
  const regex = new RegExp(`^${metricName}(?:\\{[^}]*\\})?\\s+([0-9.eE+-]+)`, 'm');
  const match = body.match(regex);
  return match ? parseFloat(match[1]) : 0;
}

// Executado uma única vez antes do início do teste de stress
export function setup() {
  const res = http.get(`${BASE_URL}/actuator/prometheus`, { timeout: '10s' });
  const initialPublished = res.status === 200
    ? extractPrometheusMetric(res.body, 'kafka_messages_published_total')
    : 0;
  const initialConsumed = res.status === 200
    ? extractPrometheusMetric(res.body, 'kafka_messages_consumed_total')
    : 0;

  console.log(`[SETUP] Estado inicial no Prometheus -> Publicadas: ${initialPublished} | Consumidas: ${initialConsumed}`);
  return { initialPublished, initialConsumed };
}

// Cenário 1: Publicação intensa no endpoint POST /kafka/publish
export function publishCustomKafkaEvent() {
  const url = `${BASE_URL}/kafka/publish`;
  const eventTypes = ['ORDER_CREATED', 'PAYMENT_CONFIRMED', 'INVENTORY_RESERVED', 'SHIPMENT_DISPATCHED'];
  const eventType = eventTypes[(__ITER + __VU) % eventTypes.length];

  // Chave variada para distribuir carga entre as 3 partições do tópico observability-events-topic
  const payload = JSON.stringify({
    key: `partition-key-${(__VU * 1000000) + __ITER}`,
    message: `[${eventType}] Stress event gerado pelo k6 (VU=${__VU}, ITER=${__ITER}, ts=${Date.now()})`,
  });

  const params = {
    headers: { 'Content-Type': 'application/json' },
    tags: { endpoint: 'kafka_publish' },
    timeout: '10s',
  };

  const res = http.post(url, payload, params);
  kafkaPublishDuration.add(res.timings.duration);

  const ok = check(res, {
    '[POST /kafka/publish] status 202 Accepted': (r) => r.status === 202,
  });

  kafkaPublishSuccessRate.add(ok);
  if (ok) {
    kafkaMessagesSent.add(1);
  }
}

// Cenário 2: Fluxo de registo de utilizador + publicação no tópico user-registrations-topic
export function registerUserAndPublishEvent() {
  const url = `${BASE_URL}/micrometer/register-user`;
  const shouldFail = Math.random() < ERROR_RATE;

  const payload = shouldFail
    ? JSON.stringify({ name: '', email: '' })
    : JSON.stringify({
        name: `StressUser_${__VU}_${__ITER}`,
        email: `stress_user_${__VU}_${__ITER}@observability.local`,
      });

  const params = {
    headers: { 'Content-Type': 'application/json' },
    tags: { endpoint: 'register_user_kafka' },
    timeout: '10s',
  };

  const res = http.post(url, payload, params);
  userRegisterDuration.add(res.timings.duration);

  if (shouldFail) {
    check(res, {
      '[POST /micrometer/register-user] falha simulada (status 500)': (r) => r.status === 500,
    });
  } else {
    const ok = check(res, {
      '[POST /micrometer/register-user] criado + enviado ao Kafka (status 201)': (r) => r.status === 201,
    });
    if (ok) {
      kafkaMessagesSent.add(1);
    }
  }
}

// Cenário 3: Consulta concorrente aos eventos consumidos em memória (GET /kafka/consumed)
export function pollConsumedKafkaEvents() {
  const url = `${BASE_URL}/kafka/consumed`;
  const params = {
    headers: { Accept: 'application/json' },
    tags: { endpoint: 'kafka_consumed' },
    timeout: '10s',
  };

  const res = http.get(url, params);
  kafkaConsumedDuration.add(res.timings.duration);

  const ok = check(res, {
    '[GET /kafka/consumed] status 200 OK': (r) => r.status === 200,
    '[GET /kafka/consumed] contém listas de eventos': (r) =>
      r.body && r.body.includes('userRegistrationEvents') && r.body.includes('customEvents'),
  });

  kafkaConsumedSuccessRate.add(ok);
}

// Executado ao final do teste para validar vazão do Producer vs Consumer no Prometheus
export function teardown(data) {
  // Aguarda 2 segundos para o Kafka Consumer processar o final da fila
  sleep(2);

  const res = http.get(`${BASE_URL}/actuator/prometheus`, { timeout: '10s' });
  if (res.status === 200) {
    const finalPublished = extractPrometheusMetric(res.body, 'kafka_messages_published_total');
    const finalConsumed = extractPrometheusMetric(res.body, 'kafka_messages_consumed_total');

    const deltaPublished = finalPublished - (data.initialPublished || 0);
    const deltaConsumed = finalConsumed - (data.initialConsumed || 0);
    const lag = Math.max(0, deltaPublished - deltaConsumed);

    console.log('================================================================');
    console.log('📊 RESUMO DO TESTE DE STRESS KAFKA (Métricas Micrometer/Prometheus)');
    console.log(`   -> Mensagens Publicadas no Teste : ${deltaPublished}`);
    console.log(`   -> Mensagens Consumidas no Teste : ${deltaConsumed}`);
    console.log(`   -> Consumer Lag Residual         : ${lag}`);
    console.log('================================================================');
  }
}
