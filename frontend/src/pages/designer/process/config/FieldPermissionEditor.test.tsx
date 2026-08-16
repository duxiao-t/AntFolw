import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { FieldPermissionEditor } from './FieldPermissionEditor';

const FIELDS = [
  { id: 'amount', label: '金额', type: 'number' },
  { id: 'proof', label: '证明文件', type: 'file_upload' },
];

describe('FieldPermissionEditor', () => {
  it('defaults to readonly and only stores non-default modes', () => {
    const onChange = vi.fn();
    render(<FieldPermissionEditor formFields={FIELDS} value={[]} onChange={onChange} />);

    fireEvent.click(screen.getAllByRole('radio', { name: '可编辑' })[0]);

    expect(onChange).toHaveBeenCalledWith([{ fieldId: 'amount', mode: 'EDITABLE' }]);
  });

  it('switching back to readonly removes the stored entry', () => {
    const onChange = vi.fn();
    render(
      <FieldPermissionEditor
        formFields={FIELDS}
        value={[{ fieldId: 'amount', mode: 'EDITABLE' }]}
        onChange={onChange}
      />,
    );

    fireEvent.click(screen.getAllByRole('radio', { name: '只读' })[0]);

    expect(onChange).toHaveBeenCalledWith([]);
  });

  it('disables editable for attachment fields', () => {
    render(<FieldPermissionEditor formFields={FIELDS} value={[]} onChange={vi.fn()} />);

    const editableButtons = screen.getAllByRole('radio', { name: '可编辑' });
    expect(editableButtons[1]).toBeDisabled();
  });
});
