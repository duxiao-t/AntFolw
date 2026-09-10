import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CcNodeConfig } from './CcNodeConfig';

const updateProps = vi.fn();
const updateName = vi.fn();

vi.mock('../useProcessDesignerStore', () => ({
  useProcessDesignerStore: (selector: (state: any) => unknown) => selector({
    updateProps,
    updateName,
  }),
}));

vi.mock('../../../../components/AssigneePicker', () => ({
  AssigneePicker: () => <div data-testid="assignee-picker" />,
}));

describe('CcNodeConfig', () => {
  beforeEach(() => {
    updateProps.mockClear();
    updateName.mockClear();
  });

  it('offers member, role and personnel-field sources', () => {
    render(
      <CcNodeConfig
        node={{ id: 'cc', type: 'CC', props: { assignedUser: [] } }}
        formFields={[{ id: 'copied', label: '抄送人', type: 'user_picker' }]}
      />,
    );

    expect(screen.getByRole('radio', { name: '指定成员' })).toBeChecked();
    expect(screen.getByRole('radio', { name: '角色' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('radio', { name: '表单中的人员' }));
    expect(updateProps).toHaveBeenCalledWith('cc', expect.objectContaining({
      assignedType: 'FIELD_USER',
    }));
  });
});
