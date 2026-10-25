import { stressThresholds } from '../thresholds.js';
import { kafkaSetup, kafkaTeardown } from '../scenarios/kafka.js';

// Os cenários (exec) precisam ser exportados pelo script principal do k6
export { publishCustomKafkaEvent, pollConsumedKafkaEvents } from '../scenarios/kafka.js';
export { registerUserAndPublishEvent } from '../scenarios/users.js';

// Taxas máximas de pico calibradas para rodar com estabilidade no Docker Desktop local (11 containers ativos)
// Podem ser sobrescritas via -e MAX_PUBLISH_RATE=200 -e MAX_REGISTER_RATE=100
const MAX_PUBLISH_RATE = parseInt(__ENV.MAX_PUBLISH_RATE || '80', 10);
const MAX_REGISTER_RATE = parseInt(__ENV.MAX_REGISTER_RATE || '40', 10);

export const options = {
  scenarios: {
    // 1. Stress de publicação direta no tópico customizado (POST /kafka/publish)
    stress_kafka_publish: {
      executor: 'ramping-arrival-rate',
      exec: 'publishCustomKafkaEvent',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 80,
      maxVUs: 80,
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
      preAllocatedVUs: 50,
      maxVUs: 50,
      stages: [
        { target: Math.round(MAX_REGISTER_RATE * 0.5), duration: '15s' },
        { target: MAX_REGISTER_RATE, duration: '30s' },
        { target: 0, duration: '10s' },
      ],
    },

    // 3. Leitura concorrente das mensagens consumidas pelos @KafkaListeners (GET /kafka/consumed)
    poll_kafka_consumed: {
      executor: 'constant-arrival-rate',
      exec: 'pollConsumedKafkaEvents',
      rate: 2,
      timeUnit: '1s',
      duration: '55s',
      preAllocatedVUs: 10,
      maxVUs: 10,
    },
  },
  thresholds: stressThresholds,
};

export function setup() {
  return kafkaSetup();
}

export function teardown(data) {
  kafkaTeardown(data);
}
