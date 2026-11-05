/** Read-only ESP32 data; bind each request to the identity supplied by the caller. */
export function createEsp32DeviceClient({ baseUrl, fetchImpl = globalThis.fetch }) {
  async function request(path, token, signal) {
    const response = await fetchImpl(`${baseUrl.replace(/\/$/, '')}/api/v1/esp32${path}`, {
      signal, headers: { Authorization: `Bearer ${token}` },
    });
    const body = await response.json().catch(() => null);
    const status = response.ok && Number.isInteger(body?.code) && body.code !== 0 ? body.code : response.status;
    if (!response.ok || (Number.isInteger(body?.code) && body.code !== 0)) {
      throw Object.assign(new Error('ESP32_REQUEST_FAILED'), { status });
    }
    return body && typeof body === 'object' && 'code' in body && 'data' in body ? body.data : body;
  }
  return {
    status: (id, token, signal) => request(`/devices/${encodeURIComponent(id)}/status`, token, signal),
    sessions: (id, token, signal) => request(`/devices/${encodeURIComponent(id)}/sessions?limit=10`, token, signal),
  };
}
