import { DndContext } from '@dnd-kit/core';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { FormRenderer } from './FormRenderer';
import type { SchemaNode } from '../../registry/types';

describe('FormRenderer', () => {
  it('keeps layout container children in the flat form value contract', () => {
    const schema: SchemaNode[] = [
      {
        id: 'row',
        type: 'span_layout',
        label: '基本信息',
        children: [
          { id: 'name', type: 'text', label: '姓名', props: { placeholder: '请输入姓名' } },
        ],
      },
      { id: 'reason', type: 'text', label: '事由', props: { placeholder: '请输入事由' } },
    ];
    const onChange = vi.fn();

    const { rerender } = render(
      <FormRenderer
        schema={schema}
        mode="runtime-fill"
        value={{}}
        onChange={onChange}
      />,
    );

    fireEvent.change(screen.getByPlaceholderText('请输入姓名'), { target: { value: '张三' } });
    expect(onChange).toHaveBeenLastCalledWith({ name: '张三' });

    rerender(
      <FormRenderer
        schema={schema}
        mode="runtime-fill"
        value={{ name: '张三' }}
        onChange={onChange}
      />,
    );
    fireEvent.change(screen.getByPlaceholderText('请输入事由'), { target: { value: '报销' } });

    expect(onChange).toHaveBeenLastCalledWith({ name: '张三', reason: '报销' });
    expect(onChange).not.toHaveBeenCalledWith(expect.objectContaining({
      row: expect.anything(),
    }));
  });

  it('renders a span-layout container inside a designer field frame', () => {
    const schema: SchemaNode[] = [
      {
        id: 'row',
        type: 'span_layout',
        label: '基本信息',
        props: { description: '填写申请基础资料' },
        children: [
          { id: 'name', type: 'text', label: '姓名', props: { placeholder: '请输入姓名' } },
        ],
      },
    ];

    const { container } = render(
      <DndContext>
        <FormRenderer schema={schema} mode="designer-preview" value={{}} />
      </DndContext>,
    );
    const fieldFrame = container.querySelector('[data-designer-field-id="row"]');
    expect(fieldFrame).toBeTruthy();
    expect(fieldFrame?.querySelector('.form-renderer__designer-card')).toBeTruthy();
  });

  it('applies per-field mode overrides and hides hidden fields', () => {
    const schema: SchemaNode[] = [
      { id: 'edit', type: 'text', label: '可编辑' },
      { id: 'ro', type: 'text', label: '只读' },
      { id: 'secret', type: 'text', label: '隐藏' },
    ];

    render(
      <FormRenderer
        schema={schema}
        mode="readonly"
        fieldModes={{ edit: 'runtime-fill', secret: 'hidden' }}
        value={{ edit: 'a', ro: 'b', secret: 'c' }}
      />,
    );

    const inputs = screen.getAllByRole('textbox');
    expect(inputs).toHaveLength(2);
    expect(inputs[0]).toHaveValue('a');
    expect(inputs[0]).not.toBeDisabled();
    expect(inputs[1]).toHaveValue('b');
    expect(inputs[1]).toBeDisabled();
    expect(screen.queryByDisplayValue('c')).not.toBeInTheDocument();
  });
});
