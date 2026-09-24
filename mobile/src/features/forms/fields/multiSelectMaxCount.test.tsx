import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { MobileSchemaNode } from '../schema/types';
import { MultiSelectField } from './MultiSelectField';

function node(maxCount: unknown = 2): MobileSchemaNode {
  return {
    id: 'tags',
    type: 'multi_select',
    label: '标签',
    props: {
      displayStyle: 'dropdown',
      maxCount,
      options: [
        { value: 'a', label: '甲' },
        { value: 'b', label: '乙' },
        { value: 'c', label: '丙' },
      ],
    },
  } as MobileSchemaNode;
}

function renderField(maxCount: unknown, value: Array<string | number> = []) {
  const onValueChange = vi.fn();
  render(
    <MultiSelectField
      node={node(maxCount)}
      value={value}
      values={{ tags: value }}
      mode="fill"
      error={undefined}
      onValueChange={onValueChange}
    />,
  );
  return onValueChange;
}

describe('mobile multi_select maxCount', () => {
  it('下拉样式下也受 maxCount 约束（原来只有内联样式判上限）', async () => {
    const user = userEvent.setup();
    const onValueChange = renderField(2);

    await user.click(screen.getByRole('button', { name: /选择标签/ }));
    await user.click(await screen.findByRole('checkbox', { name: '甲' }));
    await user.click(screen.getByRole('checkbox', { name: '乙' }));

    expect(screen.getByText('已选 2 / 2 项')).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: '丙' })).toBeDisabled();

    await user.click(screen.getByRole('checkbox', { name: '丙' }));
    await user.click(screen.getByRole('button', { name: '完成' }));

    expect(onValueChange).toHaveBeenCalledWith('tags', ['a', 'b']);
  });

  it('maxCount 为 0 / 负数 / 小数时按"没有上限"处理，不是"一个都不能选"', async () => {
    const user = userEvent.setup();
    const onValueChange = renderField(0);

    await user.click(screen.getByRole('button', { name: /选择标签/ }));
    await user.click(await screen.findByRole('checkbox', { name: '甲' }));
    await user.click(screen.getByRole('checkbox', { name: '乙' }));
    await user.click(screen.getByRole('checkbox', { name: '丙' }));
    await user.click(screen.getByRole('button', { name: '完成' }));

    expect(onValueChange).toHaveBeenCalledWith('tags', ['a', 'b', 'c']);
  });
});
