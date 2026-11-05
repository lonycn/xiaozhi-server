import { describe, it, expect } from 'vitest';
import { shallowMount } from '@vue/test-utils';
import DeviceManagement from '../src/views/DeviceManagement.vue';

const versionAllowed = DeviceManagement.methods.isGenerate;
describe('device firmware versions', () => {
  it('treats missing and malformed values as unavailable', () => {
    for (const value of [null, undefined, '', {}, [], 200, 'invalid', '2..0', '2.0-beta', '9'.repeat(400)]) {
      expect(versionAllowed({ firmwareVersion: value })).toBe(false);
    }
    expect(versionAllowed(null)).toBe(false);
  });
  it('preserves normal firmware version behavior', () => {
    expect(versionAllowed({ firmwareVersion: '1.9.9' })).toBe(false);
    expect(versionAllowed({ firmwareVersion: '2.0.0' })).toBe(true);
    expect(versionAllowed({ firmwareVersion: '2.5.2' })).toBe(true);
  });
  it('renders the real device row with a null version', async () => {
    const wrapper = shallowMount({ ...DeviceManagement, created() {}, mounted() {}, beforeUnmount() {} }, {
      global: {
        mocks: { $t: key => key, $route: { query: {} } },
        stubs: {
          ElCard: { template: '<div><slot /></div>' },
          ElButton: { template: '<button><slot /></button>' },
          CustomTable: { props: ['data'], template: '<div><div v-for="row in data" :key="row.device_id">{{ row.device_id }}<slot name="operations" :row="row" /></div></div>' },
        },
      },
    });
    await wrapper.setData({ deviceList: [{ device_id: 'device-null-version', firmwareVersion: null }] });
    expect(wrapper.text()).toContain('device-null-version');
    expect(wrapper.text()).toContain('device.unbind');
    expect(wrapper.text()).not.toContain('device.deviceThemeGeneration');
    wrapper.unmount();
  });
});
