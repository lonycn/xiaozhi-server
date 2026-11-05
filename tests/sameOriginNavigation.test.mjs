import { afterAll, beforeAll, expect, it, vi } from 'vitest';
import { pinia, useAuthStore } from '../src/stores/auth';

let router;
let removeDeviceRoute;

beforeAll(async () => {
  vi.stubEnv('VITE_DEVICE_HISTORY_ONLY', 'false');
  vi.stubEnv('VITE_DEVICE_HISTORY_URL', 'https://retired-device.invalid/');
  vi.stubEnv('VITE_DEVICE_HISTORY_CONSOLE_URL', 'https://retired-console.invalid/');
  ({ default: router } = await import('../src/router'));

  // Exercise the real navigation guards independently of the device page UI.
  removeDeviceRoute = router.addRoute({
    path: '/device-conversations',
    name: 'DeviceConversations',
    component: { render: () => null },
    meta: { internalOnly: true },
  });
  const auth = useAuthStore(pinia);
  auth.token = JSON.stringify({ token: 'internal-staff' });
  auth.userInfo = { id: '1' };
});

afterAll(() => {
  removeDeviceRoute?.();
  useAuthStore(pinia).clearAuth();
  vi.unstubAllEnvs();
});

it('opens device conversations on the current site despite retired console URLs', async () => {
  const origin = window.location.origin;
  const pathname = window.location.pathname;
  await router.push({ name: 'DeviceConversations', query: { agent_id: 'agent-1' } });

  expect(router.currentRoute.value.name).toBe('DeviceConversations');
  expect(router.currentRoute.value.query.agent_id).toBe('agent-1');
  expect(window.location.origin).toBe(origin);
  expect(window.location.pathname).toBe(pathname);
});

it('resolves the device link relative to both root and subdirectory deployments', () => {
  const href = router.resolve({ name: 'DeviceConversations', query: { agent_id: 'agent-1' } }).href;
  expect(href).toBe('#/device-conversations?agent_id=agent-1');

  for (const base of ['https://console.example.test/', 'https://console.example.test/console/']) {
    const current = new URL(base);
    const target = new URL(href, current);
    expect(target.origin).toBe(current.origin);
    expect(target.pathname).toBe(current.pathname);
    expect(target.hash).toBe('#/device-conversations?agent_id=agent-1');
  }
});
