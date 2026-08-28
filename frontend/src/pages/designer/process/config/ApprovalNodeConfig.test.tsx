import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ApprovalNodeConfig } from './ApprovalNodeConfig';

const updateProps = vi.fn();
const updateName = vi.fn();

vi.mock('../useProcessDesignerStore', () => ({
  useProcessDesignerStore: (selector: (state: any) => unknown) => selector({
    updateProps,
    updateName,
  }),
}));

describe('ApprovalNodeConfig', () => {
  it('edits the reporting manager level without extra introduction content', () => {
    render(
      <ApprovalNodeConfig
        node={{
          id: 'approval',
          type: 'APPROVAL',
          name: '直属上级审批',
          props: {
            assignedType: 'DIRECT_MANAGER',
            manager: { level: 2 },
            mode: 'OR',
          },
        }}
        formFields={[]}
      />,
    );

    expect(screen.getByRole('radio', { name: '部门主管' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: '制单人直属上级' })).toBeChecked();
    expect(screen.getByText('第几级直属上级')).toBeInTheDocument();
    expect(screen.queryByText(/功能介绍/)).not.toBeInTheDocument();
    expect(screen.queryByText('审批人为空时')).not.toBeInTheDocument();

    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '3' } });

    expect(updateProps).toHaveBeenCalledWith('approval', expect.objectContaining({
      manager: { level: 3 },
    }));
  });
});
