import { authenticatedFetch } from '../httpRequest';
const root = () => import.meta.env.VITE_API_BASE_URL || '/xiaozhi';
const base = id => `${root()}/models/${encodeURIComponent(id)}/tests`;
async function request(url, options = {}) {
  const response = await authenticatedFetch(url, options);
  const body = await response.json();
  if (!response.ok || body?.code !== 0) throw new Error(body?.msg || `HTTP ${response.status}`);
  return body.data;
}
export const startModelTest = (id, body) => request(base(id), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
export const getModelTest = (id, run) => request(`${base(id)}/${encodeURIComponent(run)}`);
export const getModelTestHistory = id => request(base(id));
export async function getModelTestAudio(id, run) {
  const response = await authenticatedFetch(`${base(id)}/${encodeURIComponent(run)}/audio`);
  if (!response.ok) throw new Error('Audio unavailable');
  return response.blob();
}
