import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApprovalNodeConfig } from './ApprovalNodeConfig';

const updateProps = vi.fn();
const updateName = vi.fn();

vi.mock('../useProcessDesignerStore', () => ({
  useProcessDesignerStore: (selector: (state: any) => unknown) => selector({
    updateProps,
    updateName,
  }),
}));

vi.mock('../../../../components/AssigneePicker', () => ({
  AssigneePicker: () => null,
}));

describe('ApprovalNodeConfig', () => {
  beforeEach(() => {
    updateProps.mockClear();
    updateName.mockClear();
  });

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

  it('offers only supported multi-approver modes', () => {
    render(
      <ApprovalNodeConfig
        node={{
          id: 'approval',
          type: 'APPROVAL',
          props: { assignedType: 'SELF', mode: 'OR' },
        }}
        formFields={[]}
      />,
    );

    expect(screen.getByRole('radio', { name: '或签（任一人操作即完成）' })).toBeChecked();
    expect(screen.getByRole('radio', { name: '会签（全员操作后判定）' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: '比例签（全员操作后判定）' })).toBeInTheDocument();
    expect(screen.queryByText('顺签')).not.toBeInTheDocument();
  });

  it('uses a fixed reject target for all-sign and ratio-sign nodes', () => {
    render(
      <ApprovalNodeConfig
        node={{
          id: 'approval',
          type: 'APPROVAL',
          props: {
            assignedType: 'SELF',
            mode: 'AND',
            rejectTargets: ['previous'],
          },
        }}
        formFields={[]}
        rejectTargets={[{ id: 'previous', label: '上一级审批' }]}
      />,
    );

    expect(screen.queryByText('允许驳回到')).not.toBeInTheDocument();
  });
});
