import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { SchemaNode } from '../../registry/types';
import MobileFormPreview, {
  collectPreviewFieldErrors,
} from './MobileFormPreview';

const schema: SchemaNode[] = [
  {
    id: 'row',
    type: 'span_layout',
    label: '基本信息',
    children: [
      {
        id: 'name',
        type: 'text',
        label: '姓名',
        props: { required: true, placeholder: '请输入姓名' },
      },
      {
        id: 'startDate',
        type: 'date',
        label: '开始日期',
        props: { required: true },
      },
    ],
  },
  {
    id: 'reason',
    type: 'textarea',
    label: '事由',
    props: { placeholder: '请输入事由' },
  },
];

describe('MobileFormPreview', () => {
  it('renders the publish preview with flat mobile form fields', () => {
    const onValueChange = vi.fn();
    const onSaveDraft = vi.fn();
    const onSubmit = vi.fn();

    const { container } = render(
      <MobileFormPreview
        title="请假申请"
        schema={schema}
        values={{}}
        errors={{ reason: '请填写事由' }}
        onValueChange={onValueChange}
        onSaveDraft={onSaveDraft}
        onSubmit={onSubmit}
      />,
    );

    expect(screen.getByTestId('mobile-form-preview')).toBeInTheDocument();
    expect(container.firstElementChild).toHaveClass('approval-mobile-preview');
    expect(screen.getByText('填写表单')).toBeInTheDocument();
    expect(screen.getByText('预览模式')).toBeInTheDocument();
    expect(screen.getByText('姓名')).toBeInTheDocument();
    expect(screen.getByText('事由')).toBeInTheDocument();
    expect(screen.getByText('请填写事由')).toBeInTheDocument();

    fireEvent.change(screen.getByPlaceholderText('请输入姓名'), {
      target: { value: '张三' },
    });
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }));
    fireEvent.click(screen.getByRole('button', { name: '提交' }));

    expect(onValueChange).toHaveBeenCalledWith('name', '张三');
    expect(onSaveDraft).toHaveBeenCalled();
    expect(onSubmit).toHaveBeenCalled();
  });

  it('collects field-level errors for mobile preview validation', () => {
    expect(collectPreviewFieldErrors(schema, {})).toMatchObject({
      name: '请填写姓名',
      startDate: '请填写开始日期',
    });
  });
});