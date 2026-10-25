import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from './common.js';

// Alocação de memória (GET /alloc/bytes) — útil para profiling no Pyroscope
export function allocBytes(mb = 1) {
  const res = http.get(`${BASE_URL}/alloc/bytes?mb=${mb}`, { tags: { endpoint: 'alloc_bytes' } });
  check(res, {
    [`[GET /alloc/bytes?mb=${mb}] status 200 OK`]: (r) => r.status === 200,
  });
}

// Criação de muitos objetos em HashMap (GET /alloc/objects) — pressão no Garbage Collector
export function allocObjects(items = 10000) {
  const res = http.get(`${BASE_URL}/alloc/objects?items=${items}`, { tags: { endpoint: 'alloc_objects' } });
  check(res, {
    [`[GET /alloc/objects?items=${items}] status 200 OK`]: (r) => r.status === 200,
  });
}

// Variantes usadas como `exec` nos cenários do alloc-test (tamanho configurável via env)
export function allocBytesScenario() {
  allocBytes(parseInt(__ENV.ALLOC_MB || '5', 10));
}

export function allocObjectsScenario() {
  allocObjects(parseInt(__ENV.ALLOC_ITEMS || '100000', 10));
}
