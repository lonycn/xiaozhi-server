import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import LegacyIcon from '../src/adapters/LegacyIcon.vue';
import { assetUrl } from '../src/adapters/assets';

describe('Vite asset and SVG adapters', () => {
  it('resolves locale artwork and dynamic avatars without a browser require shim', () => {
    expect(assetUrl('@/assets/xiaozhi-logo.png')).toMatch(/xiaozhi-logo/);
    expect(assetUrl('@/assets/user-avatar1.png')).toBeTruthy();
    expect(assetUrl('@/assets/missing-private-file.png')).toBe('');
  });
  it('renders an official SVG and forwards the existing spacing classes', () => {
    const wrapper = mount(LegacyIcon, { attrs: { class: 'el-icon-search nav-icon' } });
    expect(wrapper.find('svg').exists()).toBe(true);
    expect(wrapper.classes()).toContain('nav-icon');
    expect(wrapper.attributes('aria-hidden')).toBe('true');
  });
});
