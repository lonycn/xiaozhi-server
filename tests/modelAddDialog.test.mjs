import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { afterEach, describe, expect, it, vi } from 'vitest';
import component from '../src/components/AddModelDialog.vue';
vi.mock('../src/apis/api', () => ({ default: { model: { getModelProviders: vi.fn() } } }));
let wrapper;
afterEach(() => wrapper?.unmount());

function makeVm() {
  wrapper = mount(component, {
    props: { visible: false, modelType: 'TTS' },
    global: { plugins: [ElementPlus], mocks: { $t: key => key, $message: { error: vi.fn() } }, stubs: { CustomDialog: { template: '<div><slot /></div>' } } },
  });
  const vm = wrapper.vm;
  vm.providerFields = [
    { label: '音量', prop: 'volume', type: 'json-textarea', placeholder: '请输入volume' },
    { label: '密钥', prop: 'api_key', type: 'password', placeholder: '请输入api_key' },
  ];
  vm.formData.supplier = 'test-provider';
  return vm;
}

function submitted(vm) {
  vm.confirm();
  return wrapper.emitted('confirm')?.at(-1)?.[0];
}

describe('add dialog dict fields', () => {
  it('parses valid JSON dict input into an object before submit', () => {
    const vm = makeVm();
    vm.formData.configJson = { volume: '{"speed":1,"volume":10}', api_key: 'sk-xxx' };

    const data = submitted(vm);

    expect(data.configJson.volume).toEqual({ speed: 1, volume: 10 });
    expect(data.configJson.api_key).toBe('sk-xxx');
    expect(vm.$message.error).not.toHaveBeenCalled();
  });

  it('blocks invalid JSON input and does not emit confirm', () => {
    const vm = makeVm();
    vm.formData.configJson = { volume: '{bad json', api_key: 'sk-xxx' };

    expect(submitted(vm)).toBeUndefined();
    expect(vm.$message.error).toHaveBeenCalled();
  });

  it.each(['[1,2]', 'null', '"text"', '1'])(
    'blocks non-object JSON %s',
    (value) => {
      const vm = makeVm();
      vm.formData.configJson = { volume: value, api_key: 'sk-xxx' };

      expect(submitted(vm)).toBeUndefined();
      expect(vm.$message.error).toHaveBeenCalled();
    },
  );

  it('leaves empty strings and existing object values untouched', () => {
    const vm = makeVm();
    const obj = { speed: 2 };
    vm.formData.configJson = { volume: obj, api_key: '' };

    const data = submitted(vm);

    expect(data.configJson.volume).toEqual(obj);
    expect(data.configJson.api_key).toBe('');
    expect(vm.$message.error).not.toHaveBeenCalled();
  });
});
