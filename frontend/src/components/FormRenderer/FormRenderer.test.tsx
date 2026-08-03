import { DndContext } from '@dnd-kit/core';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { FormRenderer } from './FormRenderer';
import type { SchemaNode } from '../../registry/types';

describe('FormRenderer', () => {
  it('keeps section children in the flat form value contract', () => {
    const schema: SchemaNode[] = [
      {
        id: 'basic',
        type: 'section',
        label: '基础信息',
        children: [
          { id: 'name', type: 'text', label: '姓名', props: { placeholder: '请输入姓名' } },
        ],
      },
      {
        id: 'business',
        type: 'section',
        label: '业务信息',
        children: [
          { id: 'reason', type: 'text', label: '事由', props: { placeholder: '请输入事由' } },
        ],
      },
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
      basic: expect.anything(),
      business: expect.anything(),
    }));
  });

  it('renders a business section as one designer surface without nested section chrome', () => {
    const schema: SchemaNode[] = [
      {
        id: 'basic',
        type: 'section',
        label: '基础信息',
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
    const sectionFrame = container.querySelector('[data-designer-field-id="basic"]');
    const directSection = Array.from(sectionFrame?.children ?? []).find((child) =>
      child.classList.contains('form-renderer__business-section'),
    );
    const directDesignerCard = Array.from(sectionFrame?.children ?? []).find((child) =>
      child.classList.contains('form-renderer__designer-card'),
    );

    expect(screen.getAllByText('基础信息')).toHaveLength(1);
    expect(screen.getByText('填写申请基础资料')).toBeInTheDocument();
    expect(sectionFrame?.classList.contains('form-renderer__field--designer-bare')).toBe(true);
    expect(directSection).toBeTruthy();
    expect(directDesignerCard).toBeUndefined();
    expect(sectionFrame?.querySelectorAll('.form-renderer__business-section')).toHaveLength(1);
    expect(sectionFrame?.querySelectorAll('.form-renderer__designer-card')).toHaveLength(1);
  });
});
