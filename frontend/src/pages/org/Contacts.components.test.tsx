import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { useRef, useState, type Key } from 'react';
import { Form } from 'antd';
import {
  MemberAccountStatus,
  MemberFormModal,
  MemberGenderTag,
  MemberSearchResults,
  MembersSection,
  type MemberListItem,
} from './Contacts.components';

vi.mock('@ant-design/pro-components', () => ({
  // rowSelection 可选：人员搜索结果那张表刻意不带勾选框。
  ProTable: ({ columns, dataSource, rowSelection }: any) => (
    <table>
      <tbody>
        {dataSource.map((row: MemberListItem) => (
          <tr key={row.id}>
            {rowSelection && (
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
            )}
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
            status: 'ACTIVE',
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
            status: 'ACTIVE',
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

  it('shows the department leader badge and both account states', () => {
    const member: MemberListItem = {
      id: 9,
      employeeNo: '251803',
      username: '251803',
      displayName: '姓名很长的部门负责人',
      email: '',
      phone: '',
      position: '',
      gender: 'M',
      deptId: 2,
      status: 'DISABLED',
      wecomMapped: true,
      wecomStatus: 4,
      wecomDirectoryPresent: true,
      departmentLeader: true,
    };
    const inputRef = { current: null };

    render(
      <MembersSection
        breadcrumb="Tech"
        members={[member]}
        total={1}
        currentPage={1}
        selectedMemberIds={[]}
        deptNameById={{ 2: 'Tech' }}
        importInputRef={inputRef}
        onSelectedMemberIdsChange={vi.fn()}
        onPageChange={vi.fn()}
        onAdd={vi.fn()}
        onEdit={vi.fn()}
        onRemove={vi.fn()}
        onBulkRemove={vi.fn()}
        onExport={vi.fn()}
        onImport={vi.fn()}
        canControlLogin
      />,
    );

    expect(screen.getByText('负责人')).toBeInTheDocument();
    expect(screen.getByText('未激活')).toBeInTheDocument();
    expect(screen.getByText('禁止登录')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /允许登录/ })).toBeEnabled();
  });

  it('labels hard-disabled and removed Wecom accounts', () => {
    const base: MemberListItem = {
      id: 1,
      employeeNo: '000001',
      username: '000001',
      displayName: '成员',
      email: '',
      phone: '',
      position: '',
      gender: '',
      deptId: 2,
      status: 'DISABLED',
      wecomMapped: true,
      wecomDirectoryPresent: true,
    };

    const { rerender } = render(<MemberAccountStatus member={{ ...base, wecomStatus: 2 }} />);
    expect(screen.getByText('已禁用')).toBeInTheDocument();

    rerender(<MemberAccountStatus member={{ ...base, wecomStatus: 1, wecomDirectoryPresent: false }} />);
    expect(screen.getByText('已移出通讯录')).toBeInTheDocument();
  });

  it('requires confirmation before allowing login', async () => {
    const onSetLoginAccess = vi.fn();
    const inputRef = { current: null };
    render(
      <MembersSection
        breadcrumb="Tech"
        members={[{
          id: 9,
          employeeNo: '251803',
          username: '251803',
          displayName: '丁永久',
          email: '',
          phone: '',
          position: '',
          gender: 'M',
          deptId: 2,
          status: 'DISABLED',
          wecomMapped: true,
          wecomStatus: 4,
          wecomDirectoryPresent: true,
        }]}
        total={1}
        currentPage={1}
        selectedMemberIds={[]}
        deptNameById={{ 2: 'Tech' }}
        importInputRef={inputRef}
        onSelectedMemberIdsChange={vi.fn()}
        onPageChange={vi.fn()}
        onAdd={vi.fn()}
        onEdit={vi.fn()}
        onRemove={vi.fn()}
        onBulkRemove={vi.fn()}
        onExport={vi.fn()}
        onImport={vi.fn()}
        canControlLogin
        onSetLoginAccess={onSetLoginAccess}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: /允许登录/ }));
    expect(await screen.findByText('确定允许 丁永久 登录？')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }));
    expect(onSetLoginAccess).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: /允许登录/ }));
    fireEvent.click(await screen.findByRole('button', { name: /确\s*定/ }));
    expect(onSetLoginAccess).toHaveBeenCalledWith(expect.objectContaining({ id: 9 }), true);
  });

  it('does not allow overriding a hard-disabled Wecom account', () => {
    const inputRef = { current: null };
    render(
      <MembersSection
        breadcrumb="Tech"
        members={[{
          id: 9,
          employeeNo: '251803',
          username: '251803',
          displayName: '丁永久',
          email: '',
          phone: '',
          position: '',
          gender: 'M',
          deptId: 2,
          status: 'DISABLED',
          wecomMapped: true,
          wecomStatus: 2,
          wecomDirectoryPresent: true,
        }]}
        total={1}
        currentPage={1}
        selectedMemberIds={[]}
        deptNameById={{ 2: 'Tech' }}
        importInputRef={inputRef}
        onSelectedMemberIdsChange={vi.fn()}
        onPageChange={vi.fn()}
        onAdd={vi.fn()}
        onEdit={vi.fn()}
        onRemove={vi.fn()}
        onBulkRemove={vi.fn()}
        onExport={vi.fn()}
        onImport={vi.fn()}
        canControlLogin
      />,
    );

    expect(screen.getByRole('button', { name: /登录控制/ })).toBeDisabled();
  });
});

describe('Contacts people search results', () => {
  const base = { username: 'zhangsan', email: '', phone: '', position: '工程师', gender: '男',
    status: 'ACTIVE', employeeNo: '000003' };

  function renderResults(members: MemberListItem[], onLocate = vi.fn()) {
    render(
      <MemberSearchResults keyword="张三" members={members} total={members.length}
        currentPage={1} onPageChange={vi.fn()} deptNameById={{ 4: '技术部' }}
        onLocate={onLocate} />,
    );
    return onLocate;
  }

  it('shows the department name and offers one-click locate', () => {
    const onLocate = renderResults([{ ...base, id: 3, displayName: '张三', deptId: 4 }]);

    expect(screen.getByText('张三')).toBeInTheDocument();
    expect(screen.getByText('技术部')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '定位' }));
    expect(onLocate).toHaveBeenCalledWith(expect.objectContaining({ id: 3, deptId: 4 }));
  });

  it('does not offer a dead-end locate for a member without a department', () => {
    // 左侧树里没有"无部门"的位置，给个点了没反应的按钮比置灰更糟。
    renderResults([{ ...base, id: 9, displayName: '无部门的人', deptId: undefined as unknown as number }]);

    expect(screen.getByText('未设置部门')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '定位' })).toBeDisabled();
  });
});
