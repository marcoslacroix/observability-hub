// Configurações e helpers compartilhados entre todos os cenários k6.

// URL base (usa host.docker.internal se rodar via container Docker do k6, ou localhost se rodar k6 instalado na máquina)
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const jsonParams = (tags = {}) => ({
  headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
  tags,
  timeout: '10s',
});
