import { describe, expect, it } from 'vitest';
import { isOwnMessage, messageRole } from '../src/utils/messagePresentation.mjs';

describe('message sender presentation', () => {
  it('normalizes AI roles and keeps unknown senders distinct', () => {
    expect(messageRole('AI')).toBe('assistant');
    expect(messageRole('expert')).toBe('expert');
    expect(messageRole('other')).toBe('unknown');
  });
  it('marks only the current human sender, including replies from either staff role', () => {
    for (const sender_type of ['user', 'support_agent', 'expert']) {
      expect(isOwnMessage({ sender_type, sender_id: '42' }, 42)).toBe(true);
      expect(isOwnMessage({ sender_type, sender_id: '43' }, '42')).toBe(false);
    }
    for (const sender_type of ['assistant', 'ai', 'tool', 'system', 'other']) {
      expect(isOwnMessage({ sender_type, sender_id: '42' }, '42')).toBe(false);
    }
  });
  it('preserves account ID precision and never assumes a missing identity is self', () => {
    expect(isOwnMessage({ sender_type: 'expert', sender_id: '9223372036854775806' }, '9223372036854775806')).toBe(true);
    expect(isOwnMessage({ sender_type: 'expert', sender_id: '9223372036854775805' }, '9223372036854775806')).toBe(false);
    expect(isOwnMessage({ sender_type: 'user' }, '')).toBe(false);
    expect(isOwnMessage({ sender_type: 'user', sender_id: '' }, '')).toBe(false);
  });
});
