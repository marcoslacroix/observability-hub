import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import { BASE_URL, jsonParams } from './common.js';
import { kafkaMessagesSent } from './kafka.js';

const userRegisterDuration = new Trend('kafka_user_register_req_duration', true);

// Registro de utilizador (POST /micrometer/register-user).
// errorRate = percentual de requisições inválidas para simular falhas (0.05 = 5%)
export function registerUser(errorRate = 0, prefix = 'User') {
  const shouldFail = Math.random() < errorRate;

  const payload = shouldFail
    ? JSON.stringify({ name: '', email: '' }) // Dados inválidos -> incrementa status="failure"
    : JSON.stringify({
        name: `${prefix}_${__VU}_${__ITER}`,
        email: `${prefix.toLowerCase()}_${__VU}_${__ITER}@example.com`,
      });

  const res = http.post(`${BASE_URL}/micrometer/register-user`, payload, jsonParams({ endpoint: 'register_user' }));
  userRegisterDuration.add(res.timings.duration);

  if (shouldFail) {
    check(res, {
      '[POST /micrometer/register-user] falha esperada (status 400/500)': (r) => r.status === 400 || r.status === 500,
    });
    return false;
  }

  const ok = check(res, {
    '[POST /micrometer/register-user] status 201 Created': (r) => r.status === 201,
  });
  if (ok) {
    kafkaMessagesSent.add(1); // o registro também publica evento no tópico user-registrations-topic
  }
  return ok;
}

// Variante usada pelo stress test (executor exec) — falhas simuladas via ERROR_RATE (padrão 0)
export function registerUserAndPublishEvent() {
  registerUser(parseFloat(__ENV.ERROR_RATE || '0'), 'StressUser');
}
