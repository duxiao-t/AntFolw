import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { SchemaNode } from '../../registry/types';
import MobileFormPreview, {
  collectPreviewFieldErrors,
} from './MobileFormPreview';

const schema: SchemaNode[] = [
  {
    id: 'basic',
    type: 'section',
    label: '基础信息',
    props: { description: '填写申请人与时间' },
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
    id: 'materials',
    type: 'section',
    label: '附件材料',
    children: [
      {
        id: 'reason',
        type: 'textarea',
        label: '事由',
        props: { placeholder: '请输入事由' },
      },
    ],
  },
];

describe('MobileFormPreview', () => {
  it('renders the publish preview with the real mobile form structure', () => {
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
    expect(container.querySelector('.approval-mobile-preview__phone')).toBeNull();
    expect(screen.getByText('填写表单')).toBeInTheDocument();
    expect(screen.getByText('2 个业务分区')).toBeInTheDocument();
    expect(screen.getAllByText('基础信息')).toHaveLength(2);
    expect(screen.getAllByText('附件材料')).toHaveLength(2);
    expect(screen.getByText('1 项需补充')).toBeInTheDocument();

    fireEvent.change(screen.getByPlaceholderText('请输入姓名'), {
      target: { value: '张三' },
    });
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }));
    fireEvent.click(screen.getByRole('button', { name: '提交' }));

    expect(onValueChange).toHaveBeenCalledWith('name', '张三');
    expect(onSaveDraft).toHaveBeenCalled();
    expect(onSubmit).toHaveBeenCalled();
  });

  it('collects field-level errors for mobile preview section counters', () => {
    expect(collectPreviewFieldErrors(schema, {})).toMatchObject({
      name: '请填写姓名',
      startDate: '请填写开始日期',
    });
  });
});
