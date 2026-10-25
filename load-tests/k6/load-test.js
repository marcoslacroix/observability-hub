import { loadThresholds } from '../thresholds.js';
import { registerUser } from '../scenarios/users.js';

// Percentual de requisições inválidas para simular falhas (ex: 0.05 = 5% de erro, o que também aciona o alerta > 2% no Grafana)
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
  thresholds: loadThresholds,
};

export default function () {
  registerUser(ERROR_RATE);
}
