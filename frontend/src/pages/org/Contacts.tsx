import { PageContainer } from '@ant-design/pro-components';
import { useAccess } from '@umijs/max';
import { useIsMutating } from '@tanstack/react-query';
import { Button } from 'antd';
import { useState } from 'react';
import './Contacts.less';
import { MemberFormModal, MemberSearchResults, MembersSection } from './Contacts.components';
import { ContactsDeptDialogs } from './ContactsDeptDialogs';
import { ContactsDeptPanel } from './ContactsDeptPanel';
import { ContactsPasswordDialog } from './ContactsPasswordDialog';
import { contactsPaneMode } from './Contacts.utils';
import { useContactsDepartments } from './useContactsDepartments';
import { useContactsMemberDialogs } from './useContactsMemberDialogs';
import { useContactsMembers } from './useContactsMembers';

export default function ContactsPage() {
  return (
    <PageContainer breadcrumbRender={false}>
      <ContactsWorkbench />
    </PageContainer>
  );
}

/**
 * 通讯录工作台（单公司，不再按公司分区）：只放**跨域**的三条状态
 * （选中部门、关键词、成员页码），部门域与成员域各自在自己的 hook 里，视图只做装配。
 */
function ContactsWorkbench() {
  const access = useAccess();
  const [busy, setBusy] = useState(false);
  const [mutations] = [useIsMutating()];
  const canAdmin = !!access.canAdmin;
  const [selDeptId, setSelDeptId] = useState<number | null>(null);
  const [search, setSearch] = useState('');
  const [memberPage, setMemberPage] = useState(1);

  const dept = useContactsDepartments({
    selDeptId,
    onSelectDept: setSelDeptId,
    onLeaderSaved: () => setMemberPage(1),
  });
  const members = useContactsMembers({
    selDeptId,
    search,
    memberPage,
    setMemberPage,
    breadcrumb: dept.breadcrumb,
    onBusyChange: setBusy,
  });
  const dialogs = useContactsMemberDialogs({
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
