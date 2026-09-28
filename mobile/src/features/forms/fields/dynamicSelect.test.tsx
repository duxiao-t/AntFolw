import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { MobileFieldProps, MobileSchemaNode } from '../schema/types';
import { DynamicSelectField } from './DynamicSelectField';
import { MultiSelectField } from './MultiSelectField';
import { SelectField } from './SelectField';

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** 上游省份变更后会由测试改写这些数组，用来模拟「唯一候选」「多候选」与分页。 */
let optionRows: Array<{ value: string; label: string }> = [];
let secondPageRows: Array<{ value: string; label: string }> = [];
/** -1 表示用 firstPage 的长度当总数。 */
let optionTotal = -1;

beforeEach(() => {
  optionRows = [];
  secondPageRows = [];
  optionTotal = -1;
  vi.stubGlobal('XMLHttpRequest', undefined);
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input).includes('/api/runtime/form-options/query')) {
      const body = init?.body ? JSON.parse(String(init.body)) : {};
      const page = typeof body.page === 'number' && body.page > 1 ? body.page : 1;
      return jsonResponse({
        stage: 'OPTIONS', level: 0, totalLevels: 0,
        items: page > 1 ? secondPageRows : optionRows,
        total: optionTotal >= 0 ? optionTotal : optionRows.length,
        page, size: 20,
      });
    }
    return jsonResponse({ message: 'Not found' }, 404);
  }));
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function fieldProps(
  node: MobileSchemaNode,
  values: Record<string, unknown>,
  onValueChange = vi.fn(),
): MobileFieldProps {
  return {
    node,
    value: values[node.id],
    values,
    mode: 'fill',
    error: undefined,
    onValueChange,
    optionContext: { formCode: 'leave', formVersion: 3 },
  };
}

/** 绑定了外部数据源的依赖型下拉：候选由上游 province 过滤。 */
const boundCity: MobileSchemaNode = {
  id: 'city',
  type: 'select',
  label: '城市',
  props: {
    optionSource: {
      sourceId: 7,
      versionId: 9,
      valueColumn: 'code',
      labelColumn: 'name',
      dependency: { fieldId: 'province', matchColumn: 'code' },
    },
  },
};

const staticSupply: MobileSchemaNode = {
  id: 'supply',
  type: 'select',
  label: '用品',
  props: { options: [{ label: '纸张', value: 'paper' }] },
};

describe('带外部数据源的下拉复用普通下拉的外观', () => {
  it('静态下拉的选项是 listbox/option，并带首字母头像与选中态', async () => {
    render(<SelectField {...fieldProps(staticSupply, {})} />);
    await userEvent.click(screen.getByRole('button', { name: '选择用品' }));

    const option = await screen.findByRole('option', { name: '纸张' });
    expect(option.querySelector('.af-full-picker__avatar')).not.toBeNull();
    expect(option.querySelector('.af-full-picker__option-status')).not.toBeNull();
  });

  it('动态下拉渲染出同一套结构，而不是另一套 UI', async () => {
    optionRows = [{ value: 'SZ', label: '深圳' }];
    render(<SelectField {...fieldProps(boundCity, { province: 'GD' })} />);
    await userEvent.click(screen.getByRole('button', { name: '选择城市' }));

    const option = await screen.findByRole('option', { name: '深圳' });
    // 重构前动态下拉是裸 button、没有头像也没有选中态，这里的断言会失败。
    expect(option.querySelector('.af-full-picker__avatar')).not.toBeNull();
    expect(option.querySelector('.af-full-picker__option-status')).not.toBeNull();
    expect(screen.getByRole('listbox', { name: '城市' })).toBeInTheDocument();
  });
});

describe('弹层底部不再有分页与「关闭」', () => {
  // 头部那个 × 是弹层自带的（aria-label=关闭），保留；这里说的是**底部**那条栏。
  function footerButtons() {
    return [...document.querySelectorAll('.af-full-picker__footer button')]
      .map((b) => b.textContent.trim());
  }

  it('单选：没有底部栏，也就没有分页与关闭', async () => {
    optionRows = [{ value: 'SZ', label: '深圳' }];
    render(<SelectField {...fieldProps(boundCity, { province: 'GD' })} />);
    await userEvent.click(screen.getByRole('button', { name: '选择城市' }));

    expect(await screen.findByRole('option', { name: '深圳' })).toBeInTheDocument();
    expect(document.querySelector('.af-full-picker__footer')).toBeNull();
    expect(screen.queryByRole('button', { name: '上一页' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '下一页' })).not.toBeInTheDocument();
  });

  it('多选：底部只留「完成」用于提交草稿', async () => {
    optionRows = [{ value: 'SZ', label: '深圳' }];
    const multi = { ...boundCity, type: 'multi_select' as const };
    render(<MultiSelectField {...fieldProps(multi, { province: 'GD' })} />);
    await userEvent.click(screen.getByRole('button', { name: '选择城市' }));

    expect(await screen.findByRole('button', { name: /完成/ })).toBeInTheDocument();
    const buttons = footerButtons().map((text) => text.replace(/\s/g, ''));
    expect(buttons).toEqual(['完成（0）']);
  });
});

describe('弹层跟随字段设置，且头部保持三列结构', () => {
  it('头部始终只有三列子元素，标题才不会被挤到左边', async () => {
    optionRows = [{ value: 'SZ', label: '深圳' }];
    render(<SelectField {...fieldProps(boundCity, { province: 'GD' })} />);
    await userEvent.click(screen.getByRole('button', { name: '选择城市' }));

    expect(await screen.findByRole('option', { name: '深圳' })).toBeInTheDocument();
    // 头部是 grid: 左槽 / 标题 / ×，子元素数量一变标题就会偏。
    expect(document.querySelector('.af-full-picker__head')?.children).toHaveLength(3);
  });

  it('未勾选「支持搜索」时不渲染搜索栏', async () => {
    optionRows = [{ value: 'SZ', label: '深圳' }];
    render(<SelectField {...fieldProps(boundCity, { province: 'GD' })} />);
    await userEvent.click(screen.getByRole('button', { name: '选择城市' }));

    expect(await screen.findByRole('option', { name: '深圳' })).toBeInTheDocument();
    expect(screen.queryByRole('searchbox')).not.toBeInTheDocument();
  });

  it('勾选「支持搜索」时渲染搜索栏', async () => {
    optionRows = [{ value: 'SZ', label: '深圳' }];
    const searchable = { ...boundCity, props: { ...boundCity.props, showSearch: true } };
    render(<SelectField {...fieldProps(searchable, { province: 'GD' })} />);
    await userEvent.click(screen.getByRole('button', { name: '选择城市' }));

    expect(await screen.findByRole('searchbox')).toBeInTheDocument();
  });

  it('已选值且未关闭「允许清空」时显示清空，关闭后不显示', async () => {
    optionRows = [{ value: 'SZ', label: '深圳' }];
    const first = render(<SelectField {...fieldProps(boundCity, { province: 'GD', city: 'SZ' })} />);
    // 触发器上的显示名要等异步回查标签，先等它出现再点开。
    await userEvent.click(await screen.findByRole('button', { name: '深圳' }));
    expect(await screen.findByRole('button', { name: '清空' })).toBeInTheDocument();
    first.unmount();

    const noClear = { ...boundCity, props: { ...boundCity.props, allowClear: false } };
    render(<SelectField {...fieldProps(noClear, { province: 'GD', city: 'SZ' })} />);
    await userEvent.click(await screen.findByRole('button', { name: '深圳' }));
    expect(await screen.findByRole('option', { name: '深圳' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '清空' })).not.toBeInTheDocument();
  });

  it('列表滚到底继续取下一页，候选累加而不是替换', async () => {
    optionRows = Array.from({ length: 20 }, (_, index) => ({ value: `A${index}`, label: `甲${index}` }));
    secondPageRows = [{ value: 'B1', label: '乙1' }];
    optionTotal = 21;
    render(<SelectField {...fieldProps(boundCity, { province: 'GD' })} />);
    await userEvent.click(screen.getByRole('button', { name: '选择城市' }));

    expect(await screen.findByRole('option', { name: '甲0' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: '乙1' })).not.toBeInTheDocument();

    const list = screen.getByRole('listbox', { name: '城市' });
    Object.defineProperty(list, 'scrollHeight', { value: 1000, configurable: true });
    Object.defineProperty(list, 'clientHeight', { value: 300, configurable: true });
    list.scrollTop = 900;
    fireEvent.scroll(list);

    expect(await screen.findByRole('option', { name: '乙1' })).toBeInTheDocument();
    // 第一页仍在列表里——是累加不是替换。
    expect(screen.getByRole('option', { name: '甲0' })).toBeInTheDocument();
  });
});

describe('联动一一对应时自动选中，否则手动', () => {
  it('上游变更后过滤只剩一个候选 → 自动选中', async () => {
    optionRows = [{ value: 'SZ', label: '深圳' }];
    const onValueChange = vi.fn();
    const { rerender } = render(<DynamicSelectField {...fieldProps(boundCity, { province: 'GD' }, onValueChange)} />);
    onValueChange.mockClear();

    rerender(<DynamicSelectField {...fieldProps(boundCity, { province: 'ZJ' }, onValueChange)} />);

    await waitFor(() => expect(onValueChange).toHaveBeenCalledWith('city', 'SZ'));
  });

  it('上游变更后候选多于一个 → 清空且不自动选中，交回用户', async () => {
    optionRows = [{ value: 'SZ', label: '深圳' }, { value: 'ZH', label: '珠海' }];
    const onValueChange = vi.fn();
    const { rerender } = render(<DynamicSelectField {...fieldProps(boundCity, { province: 'GD' }, onValueChange)} />);
    onValueChange.mockClear();

    rerender(<DynamicSelectField {...fieldProps(boundCity, { province: 'GD2' }, onValueChange)} />);

    await waitFor(() => expect(onValueChange).toHaveBeenCalledWith('city', undefined));
    expect(onValueChange).not.toHaveBeenCalledWith('city', 'SZ');
    expect(onValueChange).not.toHaveBeenCalledWith('city', 'ZH');
  });

  it('已有的草稿值不因首次渲染被改写', async () => {
    optionRows = [{ value: 'SZ', label: '深圳' }];
    const onValueChange = vi.fn();
    render(<DynamicSelectField {...fieldProps(boundCity, { province: 'GD', city: 'GZ' }, onValueChange)} />);

    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(onValueChange).not.toHaveBeenCalled();
  });

  it('草稿值后到（先空后回填）也不会清掉已保存的联动值', async () => {
    // 打开草稿/返工单时外层先以空 values 渲染、随后才回填。以前这一步会被当成"用户改了上游"，
    // 把已保存的下游值清掉——静默丢数据。
    optionRows = [{ value: 'SZ', label: '深圳' }];
    const onValueChange = vi.fn();
    const { rerender } = render(<DynamicSelectField {...fieldProps(boundCity, {}, onValueChange)} />);
    onValueChange.mockClear();

    rerender(<DynamicSelectField
      {...fieldProps(boundCity, { province: 'GD', city: 'GZ' }, onValueChange)} />);

    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(onValueChange).not.toHaveBeenCalled();
  });

  it('多选不自动选中——用户可能一个都不要', async () => {
    optionRows = [{ value: 'SZ', label: '深圳' }];
    const onValueChange = vi.fn();
    const multi = { ...boundCity, type: 'multi_select' as const };
    const { rerender } = render(<MultiSelectField {...fieldProps(multi, { province: 'GD' }, onValueChange)} />);
    onValueChange.mockClear();

    rerender(<MultiSelectField {...fieldProps(multi, { province: 'ZJ' }, onValueChange)} />);

    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(onValueChange).not.toHaveBeenCalled();
  });
});
