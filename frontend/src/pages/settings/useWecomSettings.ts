import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { App, Form } from 'antd';
import dayjs from 'dayjs';
import { useEffect, useState } from 'react';
import type { FormValues, Settings } from './wecomModel';

/**
 * 公司 + 企业微信设置 + 凭证表单 + 保存。
 * 之所以留在页面壳级（而不是下沉进表单卡片）：同步卡片需要 `secretConfigured` 与 `dirty` 判断能否启动同步。
 */
export function useWecomSettings() {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<FormValues>();
  const [dirty, setDirty] = useState(false);

  const settingsQuery = useQuery({
    queryKey: ['wecom-settings'],
    queryFn: () => request<Settings>('/api/integrations/wecom/settings'),
  });
  const settings = settingsQuery.data;

  // 服务端返回即覆盖表单；secret 类字段永远回填成 undefined，避免把脱敏值当新值提交。
  useEffect(() => {
    if (!settings) return;
    form.setFieldsValue({
      corpId: settings.corpId,
      secret: undefined,
      agentId: settings.agentId,
      agentSecret: undefined,
      oauthEnabled: settings.oauthEnabled,
      jsSdkEnabled: settings.jsSdkEnabled,
      messageEnabled: settings.messageEnabled,
      scheduleEnabled: settings.scheduleEnabled ?? false,
      scheduleTime: dayjs(`2000-01-01T${settings.scheduleTime ?? '03:00:00'}`),
      scheduleMode: settings.scheduleMode ?? 'INCREMENTAL',
    });
    setDirty(false);
  }, [form, settings]);

  const saveMutation = useMutation({
    mutationFn: (values: FormValues) => request<Settings>('/api/integrations/wecom/settings', {
      method: 'PUT',
      data: {
        corpId: values.corpId.trim(),
        ...(values.secret?.trim() ? { secret: values.secret.trim() } : {}),
        agentId: values.agentId,
        ...(values.agentSecret?.trim() ? { agentSecret: values.agentSecret.trim() } : {}),
        oauthEnabled: values.oauthEnabled,
        jsSdkEnabled: values.jsSdkEnabled,
        messageEnabled: values.messageEnabled,
        scheduleEnabled: values.scheduleEnabled,
        scheduleTime: values.scheduleTime.format('HH:mm:ss'),
        scheduleMode: values.scheduleMode,
      },
    }),
    onSuccess: (saved) => {
      // 直接写入缓存而不是失效重取：回填 effect 会把用户刚填的其他字段重置成服务端值。
      queryClient.setQueryData(['wecom-settings'], saved);
      form.setFieldValue('secret', undefined);
      form.setFieldValue('agentSecret', undefined);
      setDirty(false);
      message.success('企业微信连接配置已保存');
    },
  });

  return {
    form,
    settings,
    settingsLoading: settingsQuery.isLoading,
    dirty,
    markDirty: () => setDirty(true),
    saveMutation,
  };
}
