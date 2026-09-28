import { describe, expect, it } from 'vitest';
import type { SchemaNode } from '../../registry/types';
import { currentPickerDefault } from './pickerDefaults';

const node = (type: string, props: Record<string, unknown>): SchemaNode => ({
  id: 'f1',
  type,
  props,
});

describe('picker current default', () => {
  it('只在勾了默认填充时才取值', () => {
    expect(
      currentPickerDefault(node('user_picker', {}), { id: 3 }),
    ).toBeUndefined();
  });

  it('用户选择填当前用户，部门选择填当前部门', () => {
    expect(
      currentPickerDefault(node('user_picker', { defaultToCurrent: true }), {
        id: 3,
      }),
    ).toBe(3);
    expect(
      currentPickerDefault(node('dept_picker', { defaultToCurrent: true }), {
        departmentId: 7,
      }),
    ).toBe(7);
  });

  it('两个 id 不互相顶替', () => {
    // 用户选择只有部门 id（或反过来）时不填——填错人比不填更糟。
    expect(
      currentPickerDefault(node('user_picker', { defaultToCurrent: true }), {
        departmentId: 7,
      }),
    ).toBeUndefined();
    expect(
      currentPickerDefault(node('dept_picker', { defaultToCurrent: true }), {
        id: 3,
      }),
    ).toBeUndefined();
  });

  it('多选预填一个数组，不越 maxCount', () => {
    expect(
      currentPickerDefault(
        node('user_picker', {
          defaultToCurrent: true,
          multiple: true,
          maxCount: 1,
        }),
        { id: 3 },
      ),
    ).toEqual([3]);
  });

  it('没登录态的部门是空串，按没配处理', () => {
    // /api/auth/me 在用户没有部门时返回 ""（见 LoginController.me）。
    expect(
      currentPickerDefault(node('dept_picker', { defaultToCurrent: true }), {
        departmentId: '',
      }),
    ).toBeUndefined();
    expect(
      currentPickerDefault(
        node('dept_picker', { defaultToCurrent: true }),
        undefined,
      ),
    ).toBeUndefined();
  });
});
