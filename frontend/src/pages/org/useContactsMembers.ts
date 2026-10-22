import { request } from '@umijs/max';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { App } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { blobErrorMessage } from '@/utils/format';
import type { MemberItem, UserPage } from './Contacts.types';
import {
  PAGE_SIZE,
  parseMembersCsv,
  retainVisibleKeys,
  summarizeSettledResults,
} from './Contacts.utils';

/**
 * 成员域：列表查询、跨部门搜索、分页与勾选、批量删、导入、导出、登录开关。
 *
 * 与本域之外的唯一交汇点是"当前选中部门"与关键词——两者由页面壳持有，这里只读。
 */
export function useContactsMembers({
  companyId,
  selDeptId,
  search,
  memberPage,
  setMemberPage,
  breadcrumb,
  onBusyChange,
}: {
  companyId: number;
  selDeptId: number | null;
  search: string;
  memberPage: number;
  setMemberPage: (page: number) => void;
  /** 导出文件名用当前部门名（`breadcrumb` 的最后一段）。 */
  breadcrumb: string;
  onBusyChange: (busy: boolean) => void;
}) {
  const qc = useQueryClient();
  const { message: msg } = App.useApp();
  const [selectedMemberIds, setSelectedMemberIds] = useState<React.Key[]>([]);
  const importInputRef = useRef<HTMLInputElement>(null);

  const { data: memberResult } = useQuery<UserPage>({
    queryKey: ['users-page', companyId, selDeptId, memberPage],
    queryFn: () => request('/api/users/page', { params: {
      companyId, page: memberPage, size: PAGE_SIZE, deptId: selDeptId, includeDescendants: true,
    } }),
    enabled: !!selDeptId,
  });

  // 同一个词过滤左树并在当前公司内跨部门搜人；后端继续叠加能力数据范围。
  const trimmedSearch = search.trim();
  const [searchPage, setSearchPage] = useState(1);
  useEffect(() => setSearchPage(1), [trimmedSearch]);
  const { data: searchResult, isFetching: searchLoading } = useQuery<UserPage>({
    queryKey: ['users-search', companyId, trimmedSearch, searchPage],
    queryFn: () => request('/api/users/page', { params: {
      companyId, page: searchPage, size: PAGE_SIZE, keyword: trimmedSearch,
    } }),
    enabled: !!trimmedSearch,
  });

  const members = memberResult?.records ?? [];

  useEffect(() => setMemberPage(1), [selDeptId]);

  useEffect(() => {
    const visibleIds = new Set(members.map((m) => m.id));
    setSelectedMemberIds((prev) => retainVisibleKeys(prev, visibleIds));
  }, [members]);

  const refreshMembers = async () => {
    await Promise.all(['users-page', 'users-search', 'leader-users', 'manager-candidates']
      .map((key) => qc.invalidateQueries({ queryKey: [key] })));
  };

  // --- member CRUD ---
  const memberRemove = useMutation({
    mutationFn: (id: number) => request(`/api/users/${id}`, { method: 'DELETE' }),
    onSuccess: async () => { await refreshMembers(); msg.success('已删除'); },
  });
  const memberLoginAccess = useMutation({
    mutationFn: ({ id, enabled }: { id: number; enabled: boolean }) =>
      request(`/api/users/${id}/login-access`, { method: 'PUT', data: { enabled } }),
    onSuccess: async (_data, variables) => {
      await qc.invalidateQueries({ queryKey: ['users-page'] });
      msg.success(variables.enabled ? '已允许该成员登录' : '已禁止该成员登录');
    },
  });

  const handleBulkMemberRemove = async () => {
    const ids = selectedMemberIds.map(Number);
    if (!ids.length) return;
    onBusyChange(true);
    try {
      const results = await Promise.allSettled(
        ids.map((id) => request(`/api/users/${id}`, { method: 'DELETE' })),
      );
      const { successCount, failedCount } = summarizeSettledResults(results);
      if (successCount) {
        setSelectedMemberIds([]);
        await refreshMembers();
      }
      if (failedCount) msg.error(`批量删除完成：成功 ${successCount} 条，失败 ${failedCount} 条`);
      else msg.success(`已删除 ${successCount} 名成员`);
    } finally {
      onBusyChange(false);
    }
  };

  // 导出走后端（同一条 listAuthorized：含下级部门、行级数据范围也在），以前只把**当前页**
  // 的 15 个人拼成 CSV，一页 15 人时导出看起来"成功"却少了人。
  const handleExportMembers = async () => {
    if (!selDeptId) { msg.warning('请先选择部门'); return; }
    try {
      const blob = await request('/api/users/export', {
        params: { companyId, deptId: selDeptId, includeDescendants: true, format: 'csv' },
        responseType: 'blob',
        skipErrorHandler: true,
      });
      const url = URL.createObjectURL(blob as Blob);
      const anchor = document.createElement('a');
      const deptName = breadcrumb.split(' / ').pop() || '部门成员';
      anchor.href = url;
      anchor.download = `${deptName}-成员.csv`;
      anchor.click();
      URL.revokeObjectURL(url);
      msg.success('已开始下载');
    } catch (error) {
      msg.error(await blobErrorMessage(error));
    }
  };

  const handleImportMembers = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file || !selDeptId) return;

    onBusyChange(true);
    try {
      const result = parseMembersCsv(await file.text(), selDeptId);
      if (result.errors.length) {
        msg.error(result.errors.slice(0, 3).join('；'));
        return;
      }
      if (!result.rows.length) { msg.warning('CSV 中没有可导入的成员'); return; }

      const imported = await request<{
        successCount: number; failedCount: number; passwordResetRequired: boolean;
      }>('/api/users/import', { method: 'POST', data: { users: result.rows } });
      const { successCount, failedCount } = imported;
      if (successCount) await refreshMembers();
      // 只要建成了账号就得提醒重置密码：后端给的是随机且不返回的密码，这些人自己登不上。
      // 部分成功时以前只报失败数，把这条盖掉了。
      const resetHint = successCount ? '；请由管理员为新账号重置密码' : '';
      if (failedCount) {
        msg.error(`导入完成：成功 ${successCount} 条，失败 ${failedCount} 条${resetHint}`);
      } else {
        msg.success(`已导入 ${successCount} 名成员${resetHint}`);
      }
    } catch (_error) {
      msg.error('批量导入失败');
    } finally {
      onBusyChange(false);
    }
  };

  return {
    members,
    total: memberResult?.total ?? 0,
    selectedMemberIds,
    setSelectedMemberIds,
    importInputRef,
    trimmedSearch,
    searchResult,
    searchLoading,
    searchPage,
    setSearchPage,
    refreshMembers,
    removeMember: (id: number) => memberRemove.mutate(id),
    bulkRemove: handleBulkMemberRemove,
    exportMembers: handleExportMembers,
    importMembers: handleImportMembers,
    loginAccessLoadingId: memberLoginAccess.isPending ? memberLoginAccess.variables?.id : undefined,
    setLoginAccess: (member: MemberItem, enabled: boolean) =>
      memberLoginAccess.mutate({ id: member.id, enabled }),
  };
}
