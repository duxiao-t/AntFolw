import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ApprovalCommentEditor } from '.';

describe('ApprovalCommentEditor', () => {
  it('replaces the editable comment with a selected node preset', () => {
    const onChange = vi.fn();
    const { rerender } = render(
      <ApprovalCommentEditor
        action="approve"
        presets={{ approve: ['资料齐全'], reject: ['请补充附件'] }}
        value="手工输入"
        onChange={onChange}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '资料齐全' }));
    expect(onChange).toHaveBeenCalledWith('资料齐全');

    rerender(
      <ApprovalCommentEditor
        action="approve"
        presets={{ approve: ['资料齐全'], reject: ['请补充附件'] }}
        value="资料齐全"
        onChange={onChange}
      />,
    );
    fireEvent.change(screen.getByPlaceholderText('审批意见（可选）'), {
      target: { value: '资料齐全，同意' },
    });
    expect(onChange).toHaveBeenLastCalledWith('资料齐全，同意');
  });
});
