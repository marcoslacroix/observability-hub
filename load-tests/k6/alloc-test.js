import { allocThresholds } from '../thresholds.js';

// Os cenários (exec) precisam ser exportados pelo script principal do k6
export { allocBytesScenario, allocObjectsScenario } from '../scenarios/alloc.js';

// Tamanhos calibráveis via -e ALLOC_MB=10 -e ALLOC_ITEMS=200000 (limites da API: mb ≤ 100, items ≤ 1.000.000)
// Taxas baixas de propósito: cada requisição aloca bastante memória na JVM.
export const options = {
  scenarios: {
    // 1. Blocos de byte[] de 1 MB (GET /alloc/bytes)
    alloc_bytes: {
      executor: 'ramping-arrival-rate',
      exec: 'allocBytesScenario',
      startRate: 1,
      timeUnit: '1s',
      preAllocatedVUs: 10,
      maxVUs: 30,
      stages: [
        { target: 5, duration: '15s' },  // Aquecimento
        { target: 10, duration: '30s' }, // Carga sustentada
        { target: 0, duration: '10s' },  // Ramp-down
      ],
    },

    // 2. Objetos em HashMap — pressão no GC (GET /alloc/objects)
    alloc_objects: {
      executor: 'ramping-arrival-rate',
      exec: 'allocObjectsScenario',
      startRate: 1,
      timeUnit: '1s',
      preAllocatedVUs: 10,
      maxVUs: 30,
      stages: [
        { target: 5, duration: '15s' },
        { target: 10, duration: '30s' },
        { target: 0, duration: '10s' },
      ],
    },
  },
  thresholds: allocThresholds,
};
