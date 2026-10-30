import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import component from '../src/components/ModelEditDialog.vue';
vi.mock('../src/apis/api', () => ({ default: { model: { getModelProviders: vi.fn() } } }));
let wrapper;
let vm;
let saved;

function openModel(value) {
  vm.processModelData({
    id: 'test-model',
    modelName: 'Test model',
    isEnabled: 1, isDefault: 0,
    configJson: { type: 'test-provider', prosody: value },
  });
}

beforeEach(() => {
  vi.useFakeTimers();
  saved = vi.fn();
  wrapper = mount(component, {
    props: { modelType: 'TTS', modelData: { id: 'test-model' }, onSave: saved },
    global: { plugins: [ElementPlus], mocks: { $t: key => key, $message: { error: vi.fn() } }, stubs: { CustomDialog: { template: '<div><slot /></div>' } } },
  });
  vm = wrapper.vm;
  vm.dynamicCallInfoFields = [{ prop: 'prosody', type: 'json-textarea' }];

});

afterEach(() => {
  wrapper.unmount();
  vi.clearAllTimers();
  vi.useRealTimers();
});

describe('stored dictionary fields', () => {
  it('opens a JSON string as an editable dictionary instead of a quoted string', () => {
    openModel('{"speed":1,"volume":10}');

    expect(JSON.parse(vm.fieldJsonMap.prosody)).toEqual({ speed: 1, volume: 10 });
    expect(vm.form.configJson.prosody).toEqual({ speed: 1, volume: 10 });
  });

  it('preserves a stored JSON string when saving without changes', () => {
    openModel('{"speed":1,"volume":10}');
    vm.handleSave();

    expect(vm.$message.error).not.toHaveBeenCalled();
    expect(saved.mock.calls[0][0].formData.configJson.prosody).toEqual({
      speed: 1,
      volume: 10,
    });
  });

  it('keeps edits after saving and reopening the returned object', () => {
    openModel('{"speed":1,"volume":10}');
    vm.fieldJsonMap.prosody = '{"speed":2,"volume":5}';
    vm.handleSave();
    const config = saved.mock.calls[0][0].formData.configJson;

    vm.resetForm();
    openModel(JSON.parse(JSON.stringify(config.prosody)));
    vm.handleSave();

    expect(JSON.parse(vm.fieldJsonMap.prosody)).toEqual({ speed: 2, volume: 5 });
    expect(saved.mock.calls[1][0].formData.configJson.prosody).toEqual({
      speed: 2,
      volume: 5,
    });
    expect(vm.$message.error).not.toHaveBeenCalled();
  });

  it('preserves nested values and masked secrets in existing dictionary objects', () => {
    const value = { speed: 0, enabled: false, nested: { api_key: 'ab****yz' }, tags: ['a'] };
    openModel(value);
    vm.handleSave();

    expect(saved.mock.calls[0][0].formData.configJson.prosody).toEqual(value);
    expect(vm.$message.error).not.toHaveBeenCalled();
  });

  it.each(['not json', 'null', '[]', '1', 'true', '"text"'])(
    'does not reinterpret %s as a dictionary',
    (value) => {
      openModel(value);

      expect(JSON.parse(vm.fieldJsonMap.prosody)).toBe(value);
      expect(vm.validateJson(vm.fieldJsonMap.prosody)).toBeNull();
      expect(vm.$message.error).toHaveBeenCalled();
    },
  );
});
