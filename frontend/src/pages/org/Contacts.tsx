import { PageContainer } from '@ant-design/pro-components';
import { request, useAccess } from '@umijs/max';
import { useQuery, useIsMutating } from '@tanstack/react-query';
import { Button, Select } from 'antd';
import { useState } from 'react';
import './Contacts.less';
import { MemberFormModal, MemberSearchResults, MembersSection } from './Contacts.components';
import { ContactsDeptDialogs } from './ContactsDeptDialogs';
import { ContactsDeptPanel } from './ContactsDeptPanel';
import { ContactsPasswordDialog } from './ContactsPasswordDialog';
import type { CompanyOption } from './Contacts.types';
import { contactsPaneMode } from './Contacts.utils';
import { useContactsDepartments } from './useContactsDepartments';
import { useContactsMemberDialogs } from './useContactsMemberDialogs';
import { useContactsMembers } from './useContactsMembers';

export default function ContactsPage() {
  const [selectedCompanyId, setSelectedCompanyId] = useState<number>();
  const [busy, setBusy] = useState(false);
  const mutations = useIsMutating();
  const companies = useQuery({
    queryKey: ['companies'],
    queryFn: () => request<AntFlowAPI.Company[]>('/api/companies'),
  });
  const options = (companies.data ?? []).flatMap((company) => company.id == null ? []
    : [{ value: company.id, label: company.name ?? `公司 ${company.id}` }]);
  const companyId = options.some((option) => option.value === selectedCompanyId)
    ? selectedCompanyId : options.length === 1 ? options[0].value : undefined;
  return (
    <PageContainer breadcrumbRender={false}>
      <Select
        aria-label="通讯录公司"
        placeholder="请选择公司"
        value={companyId}
        options={options}
        loading={companies.isFetching}
        disabled={busy || mutations > 0}
        onChange={setSelectedCompanyId}
        style={{ width: 280, marginBottom: 12 }}
      />
      {companies.isError && <Button onClick={() => companies.refetch()}>公司加载失败，点击重试</Button>}
      {companyId == null ? (
        <div className="ct-empty">{options.length ? '请先选择公司' : '暂无可访问的公司'}</div>
      ) : (
        <CompanyContacts key={companyId} companyId={companyId} companies={options} onBusyChange={setBusy} />
      )}
    </PageContainer>
  );
}

/**
 * 单个公司的通讯录工作台：这里只放**跨域**的三条状态（选中部门、关键词、成员页码），
 * 部门域与成员域各自在自己的 hook 里，视图只做装配。
 */
function CompanyContacts({ companyId, companies, onBusyChange }: {
  companyId: number;
  companies: CompanyOption[];
  onBusyChange: (busy: boolean) => void;
}) {
  const access = useAccess();
  const canAdmin = !!access.canAdmin;
  const [selDeptId, setSelDeptId] = useState<number | null>(null);
  const [search, setSearch] = useState('');
  const [memberPage, setMemberPage] = useState(1);

  const dept = useContactsDepartments({
    companyId,
    selDeptId,
    onSelectDept: setSelDeptId,
    onLeaderSaved: () => setMemberPage(1),
  });
  const members = useContactsMembers({
    companyId,
    selDeptId,
    search,
    memberPage,
    setMemberPage,
    breadcrumb: dept.breadcrumb,
    onBusyChange,
  });
  const dialogs = useContactsMemberDialogs({
    companyId,
    selDeptId,
    canAdmin,
    refreshMembers: members.refreshMembers,
  });

  const paneMode = contactsPaneMode(search, selDeptId);

  return (
    <>
      {dept.departmentsError && <Button onClick={() => dept.refetchDepartments()}>部门加载失败，点击重试</Button>}
      <div className="ct-layout">
        <ContactsDeptPanel
          departments={dept.deptList}
          treeData={dept.treeData}
          search={search}
          onSearchChange={setSearch}
          canCreateRoot={!!access.canOrgDepartmentManage}
          onAddRoot={() => dept.openAdd(null)}
          selectedDeptId={selDeptId}
          onSelectDept={setSelDeptId}
          onDrop={dept.onDrop}
          actions={dept.actions}
        />

        <main className="ct-right">
          {paneMode === 'search' ? (
            <MemberSearchResults
              keyword={members.trimmedSearch}
              loading={members.searchLoading}
              members={members.searchResult?.records ?? []}
              total={members.searchResult?.total ?? 0}
              currentPage={members.searchPage}
              onPageChange={members.setSearchPage}
              deptNameById={dept.deptNameById}
              onLocate={(member) => {
                // 定位 = 清掉关键词、选中他的部门：回到"那个部门的成员列表"。
                setSearch('');
                setMemberPage(1);
                setSelDeptId(member.deptId);
              }}
            />
          ) : paneMode === 'members' ? (
            <MembersSection
              breadcrumb={dept.breadcrumb}
              members={members.members}
              total={members.total}
              currentPage={memberPage}
              selectedMemberIds={members.selectedMemberIds}
              deptNameById={dept.deptNameById}
              importInputRef={members.importInputRef}
              onSelectedMemberIdsChange={members.setSelectedMemberIds}
              onPageChange={setMemberPage}
              onAdd={dialogs.openAdd}
              onEdit={dialogs.openEdit}
              onRemove={members.removeMember}
              onBulkRemove={members.bulkRemove}
              onExport={members.exportMembers}
              onImport={members.importMembers}
              canAdd={!!dept.selectedDepartment?.canManageUsers}
              canResetPassword={canAdmin}
              canControlLogin={canAdmin}
              canManageUsersByDept={dept.canManageUsersByDept}
              onResetPassword={dialogs.openPasswordReset}
              onSetLoginAccess={members.setLoginAccess}
              loginAccessLoadingId={members.loginAccessLoadingId}
            />
          ) : (
            <div className="ct-empty">请从左侧选择部门，或在上方搜索部门与人员</div>
          )}
        </main>
      </div>

      <ContactsDeptDialogs
        companyLabel={companies.find((company) => company.value === companyId)?.label}
        departments={dept.deptList}
        form={dept.deptForm}
        addOpen={dept.deptAddOpen}
        addParentId={dept.deptAddParentId}
        createPending={dept.deptCreatePending}
        onAddOk={dept.handleDeptAdd}
        onAddCancel={dept.cancelDeptAdd}
        editOpen={dept.deptEditOpen}
        editName={dept.editDeptName}
        updatePending={dept.deptUpdatePending}
        onEditOk={dept.handleDeptEdit}
        onEditCancel={dept.cancelDeptEdit}
        leaderOpen={dept.leaderOpen}
        leaderUsers={dept.leaderUsers}
        leaderCurrentIds={dept.leaderCurrentIds}
        leaderSaving={dept.deptUpdatePending}
        onLeaderOk={dept.handleLeaderSet}
        onLeaderCancel={dept.cancelLeader}
      />

      <MemberFormModal
        open={dialogs.memberOpen}
        editing={dialogs.memberEdit}
        form={dialogs.memberForm}
        saving={dialogs.saving}
        onOk={dialogs.handleMemberOk}
        onCancel={dialogs.closeMember}
        managerOptions={dialogs.managerOptions}
        managerLoading={dialogs.managerCandidatesLoading}
        onManagerSearch={dialogs.setManagerKeyword}
        companies={companies}
        canChangeCompany={dialogs.canChangeCompany}
        onCompanyChange={dialogs.resetDepartmentOnCompanyChange}
        departments={dialogs.memberDepartmentsReady}
        departmentsLoading={dialogs.memberDepartments.isFetching}
        canResetPassword={canAdmin}
        onResetPassword={dialogs.resetPasswordFromEditor}
      />

      <ContactsPasswordDialog
        target={dialogs.passwordTarget}
        form={dialogs.passwordForm}
        saving={dialogs.passwordSaving}
        onOk={dialogs.handlePasswordReset}
        onCancel={dialogs.closePassword}
      />
    </>
  );
}
