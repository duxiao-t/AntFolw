import { renderHook, waitFor, act } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useDynamicOptions } from './dynamicOptions';

const { requestMock } = vi.hoisted(() => ({ requestMock: vi.fn() }));
vi.mock('@umijs/max', () => ({ request: requestMock }));

const node = {
  id: 'category',
  type: 'select',
  label: '分类',
  props: { optionSource: { sourceId: 1, versionId: 2 } },
} as any;
const context = { formCode: 'leave' };

beforeEach(() => requestMock.mockReset());

describe('useDynamicOptions 分步（LEVEL）翻页', () => {
  it('某一级候选超过一页时 hasMore 为真，loadMore 取下一页', async () => {
    requestMock.mockImplementation((async (...args: any[]) => {
      const page = args[1]?.data?.page as number;
      return {
        stage: 'LEVEL',
        level: 0,
        totalLevels: 2,
        // 一级分类共 25 个、每页 20：page 1 只能拿到 20 条。
        total: 25,
        items: page === 1
          ? Array.from({ length: 20 }, (_, i) => ({ value: `L${i}`, label: `L${i}` }))
          : Array.from({ length: 5 }, (_, i) => ({ value: `L${20 + i}`, label: `L${20 + i}` })),
      };
    }) as any);

    const { result } = renderHook(() => useDynamicOptions(node, {}, context));
    await waitFor(() => expect(result.current.options).toHaveLength(20));

    expect(result.current.stage).toBe('LEVEL');
    expect(result.current.hasMore).toBe(true);

    act(() => result.current.loadMore());
    await waitFor(() => expect(result.current.options).toHaveLength(25));
    expect(result.current.hasMore).toBe(false);
  });
});
