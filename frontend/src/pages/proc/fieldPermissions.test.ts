import { describe, expect, it } from 'vitest';
import { pickEditableValues } from './fieldPermissions';
import type { FieldMode } from '../../registry/types';

describe('pickEditableValues', () => {
  it('keeps only runtime-fill fields', () => {
    const values = { amount: 5, name: '张三', secret: 'x' };
    const modes: Record<string, FieldMode> = { amount: 'runtime-fill', name: 'readonly' };

    expect(pickEditableValues(values, modes)).toEqual({ amount: 5 });
  });

  it('returns empty object when nothing is editable', () => {
    const modes: Record<string, FieldMode> = { a: 'readonly' };
    expect(pickEditableValues({ a: 1 }, modes)).toEqual({});
    expect(pickEditableValues({ a: 1 }, {})).toEqual({});
  });
});
