import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { smokeThresholds } from '../thresholds.js';
import { BASE_URL } from '../scenarios/common.js';
import { publishCustomKafkaEvent, pollConsumedKafkaEvents } from '../scenarios/kafka.js';
import { registerUser } from '../scenarios/users.js';
import { allocBytes } from '../scenarios/alloc.js';

// Teste rápido de fumaça: executa 1 iteração leve de cada cenário, sem falhas simuladas
export const options = {
  vus: 2,
  duration: '15s',
  thresholds: smokeThresholds,
};

export default function () {
  group('Actuator Health', () => {
    const res = http.get(`${BASE_URL}/actuator/health`);
    check(res, {
      '[GET /actuator/health] status 200 OK': (r) => r.status === 200,
      '[GET /actuator/health] aplicação UP': (r) => r.body && r.body.includes('"status":"UP"'),
    });
  });

  group('Usuários', () => registerUser(0, 'SmokeUser'));

  group('Kafka', () => {
    publishCustomKafkaEvent();
    pollConsumedKafkaEvents();
  });

  group('Alocação de memória', () => allocBytes(1));

  sleep(1);
}
