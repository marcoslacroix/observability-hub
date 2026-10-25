import http from 'k6/http';
import { check } from 'k6';

// URL base (usa host.docker.internal se rodar via container Docker do k6, ou localhost se rodar k6 instalado na máquina)
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Percentual de requisições inválidas para simular falhas (ex: 0.05 = 5% de erro 500, o que também aciona o alerta > 2% no Grafana)
const ERROR_RATE = parseFloat(__ENV.ERROR_RATE || '0.05');

export const options = {
  scenarios: {
    high_rps_register_user: {
      executor: 'ramping-arrival-rate',
      startRate: 50,          // Começa com 50 req/s
      timeUnit: '1s',
      preAllocatedVUs: 50,    // VUs pré-alocadas
      maxVUs: 300,            // Limite máximo de VUs simultâneas
      stages: [
        { target: 200, duration: '20s' }, // Sobe até 200 req/s em 20s
        { target: 300, duration: '40s' }, // Sustenta 300 req/s por 40s
        { target: 0, duration: '10s' },   // Desacelera até 0 em 10s
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<500'], // 95% das requisições devem responder em menos de 500ms
  },
};

export default function () {
  const url = `${BASE_URL}/micrometer/register-user`;
  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
  };

  // Define se esta requisição será de sucesso ou de falha simulada
  const shouldFail = Math.random() < ERROR_RATE;

  const payload = shouldFail
    ? JSON.stringify({ name: '', email: '' }) // Dados inválidos -> incrementa status="failure" e gera HTTP 500
    : JSON.stringify({
        name: `User_${__VU}_${__ITER}`,
        email: `user_${__VU}_${__ITER}@example.com`,
      });

  const res = http.post(url, payload, params);

  if (shouldFail) {
    check(res, {
      'falha esperada (status 500)': (r) => r.status === 500,
    });
  } else {
    check(res, {
      'utilizador criado (status 201)': (r) => r.status === 201,
    });
  }
}
