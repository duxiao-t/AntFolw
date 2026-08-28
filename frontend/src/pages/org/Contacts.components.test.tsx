import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { useRef, useState, type Key } from 'react';
import { Form } from 'antd';
import { MemberFormModal, MemberGenderTag, MembersSection, type MemberListItem } from './Contacts.components';

vi.mock('@ant-design/pro-components', () => ({
  ProTable: ({ columns, dataSource, rowSelection }: any) => (
    <table>
      <tbody>
        {dataSource.map((row: MemberListItem) => (
          <tr key={row.id}>
            <td>
              <input
                aria-label={`select-${row.id}`}
                type="checkbox"
                checked={rowSelection.selectedRowKeys.includes(row.id)}
                onChange={(event) => {
                  const next = event.currentTarget.checked
                    ? [...rowSelection.selectedRowKeys, row.id]
                    : rowSelection.selectedRowKeys.filter((id: number) => id !== row.id);
                  rowSelection.onChange(next);
                }}
              />
            </td>
            {columns.map((column: any) => (
              <td key={column.key ?? column.dataIndex}>
                {column.render
                  ? column.render(row[column.dataIndex as keyof MemberListItem], row)
                  : row[column.dataIndex as keyof MemberListItem]}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  ),
}));

describe('Contacts components', () => {
  it('renders legacy male gender with the same display label', () => {
    render(<MemberGenderTag value="男" />);

    expect(screen.getByText('男')).toBeInTheDocument();
  });

  it('enables bulk delete after selecting a member', () => {
    function Harness() {
      const [selectedKeys, setSelectedKeys] = useState<Key[]>([]);
      const inputRef = useRef<HTMLInputElement>(null);
      return (
        <MembersSection
          breadcrumb="Tech"
          members={[{
            id: 1,
            employeeNo: '000001',
            username: 'bob',
            displayName: 'Bob',
            email: '',
            phone: '',
            position: '',
            gender: 'M',
            deptId: 2,
            managerId: 2,
            managerDisplayName: '张经理',
          }]}
          total={1}
          currentPage={1}
          selectedMemberIds={selectedKeys}
          deptNameById={{ 2: 'Tech' }}
          importInputRef={inputRef}
          onSelectedMemberIdsChange={setSelectedKeys}
          onPageChange={vi.fn()}
          onAdd={vi.fn()}
          onEdit={vi.fn()}
          onRemove={vi.fn()}
          onBulkRemove={vi.fn()}
          onExport={vi.fn()}
          onImport={vi.fn()}
        />
      );
    }

    render(<Harness />);

    expect(screen.getByText('张经理')).toBeInTheDocument();

    const bulkDelete = screen.getByRole('button', { name: /批量删除/ });
    expect(bulkDelete).toBeDisabled();

    fireEvent.click(screen.getByLabelText('select-1'));

    expect(screen.getByRole('button', { name: /批量删除/ })).toBeEnabled();
  });

  it('shows the current reporting manager in the member editor', () => {
    function Harness() {
      const [form] = Form.useForm();
      return (
        <MemberFormModal
          open
          editing={{
            id: 1,
            employeeNo: '000001',
            username: 'bob',
            displayName: 'Bob',
            email: '',
            phone: '',
            position: '',
            gender: 'M',
            deptId: 2,
            managerId: 2,
            managerDisplayName: '张经理',
          }}
          form={form}
          saving={false}
          onOk={vi.fn()}
          onCancel={vi.fn()}
          managerOptions={[{ value: 2, label: '张经理（000002）' }]}
          onManagerSearch={vi.fn()}
        />
      );
    }

    render(<Harness />);

    expect(screen.getByText('直属上级')).toBeInTheDocument();
    expect(screen.getByText('张经理（000002）')).toBeInTheDocument();
  });
});
