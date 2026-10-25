import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { BASE_URL, jsonParams } from './common.js';

// Percentual de falhas simuladas (padrão: 0 para focar 100% no stress do Kafka; use ex: 0.05 para 5% de erro)
const ERROR_RATE = parseFloat(__ENV.ERROR_RATE || '0');

// Métricas customizadas do k6 para observabilidade por fluxo
export const kafkaPublishDuration = new Trend('kafka_publish_req_duration', true);
export const kafkaConsumedDuration = new Trend('kafka_consumed_req_duration', true);
export const kafkaMessagesSent = new Counter('k6_kafka_messages_triggered_total');
export const kafkaPublishSuccessRate = new Rate('kafka_publish_success_rate');
export const kafkaConsumedSuccessRate = new Rate('kafka_consumed_success_rate');

// Extrai o valor de uma métrica (com status="success") do endpoint /actuator/prometheus
function extractPrometheusMetric(body, metricName) {
  if (!body) return 0;
  const regexWithSuccess = new RegExp(`^${metricName}\\{[^}]*status="success"[^}]*\\}\\s+([0-9.eE+-]+)`, 'm');
  const matchSuccess = body.match(regexWithSuccess);
  if (matchSuccess) return parseFloat(matchSuccess[1]);

  const regexFallback = new RegExp(`^${metricName}(?:\\{[^}]*\\})?\\s+([0-9.eE+-]+)`, 'm');
  const matchFallback = body.match(regexFallback);
  return matchFallback ? parseFloat(matchFallback[1]) : 0;
}

// Para ser usado como setup() do teste: captura o estado inicial no Prometheus
export function kafkaSetup() {
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

// Para ser usado como teardown() do teste: valida vazão do Producer vs Consumer no Prometheus
export function kafkaTeardown(data) {
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

// Publicação no endpoint POST /kafka/publish
export function publishCustomKafkaEvent() {
  const shouldFail = Math.random() < ERROR_RATE;
  const eventTypes = ['ORDER_CREATED', 'PAYMENT_CONFIRMED', 'INVENTORY_RESERVED', 'SHIPMENT_DISPATCHED'];
  const eventType = eventTypes[(__ITER + __VU) % eventTypes.length];

  // Chave variada para distribuir carga entre as 3 partições do tópico observability-events-topic
  const payload = shouldFail
    ? JSON.stringify({ key: '', message: '' })
    : JSON.stringify({
        key: `partition-key-${(__VU * 1000000) + __ITER}`,
        message: `[${eventType}] Evento gerado pelo k6 (VU=${__VU}, ITER=${__ITER}, ts=${Date.now()})`,
      });

  const res = http.post(`${BASE_URL}/kafka/publish`, payload, jsonParams({ endpoint: 'kafka_publish' }));
  kafkaPublishDuration.add(res.timings.duration);

  if (shouldFail) {
    check(res, {
      '[POST /kafka/publish] falha de validação esperada (status 400)': (r) => r.status === 400 || r.status === 500,
    });
  } else {
    const ok = check(res, {
      '[POST /kafka/publish] status 202 Accepted': (r) => r.status === 202,
    });

    kafkaPublishSuccessRate.add(ok);
    if (ok) {
      kafkaMessagesSent.add(1);
    }
  }
}

// Consulta aos eventos consumidos em memória (GET /kafka/consumed)
export function pollConsumedKafkaEvents() {
  const res = http.get(`${BASE_URL}/kafka/consumed`, jsonParams({ endpoint: 'kafka_consumed' }));
  kafkaConsumedDuration.add(res.timings.duration);

  const ok = check(res, {
    '[GET /kafka/consumed] status 200 OK': (r) => r.status === 200,
    '[GET /kafka/consumed] contém listas de eventos': (r) =>
      r.body && r.body.includes('userRegistrationEvents') && r.body.includes('customEvents'),
  });

  kafkaConsumedSuccessRate.add(ok);
}
