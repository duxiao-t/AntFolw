import { PageContainer } from '@ant-design/pro-components';
import { Col, Row } from 'antd';
import { WecomAccessCard } from './WecomAccessCard';
import { WecomConnectionCard } from './WecomConnectionCard';
import { WecomReadinessCard } from './WecomReadinessCard';
import { WecomSyncCard } from './WecomSyncCard';
import { useWecomSettings } from './useWecomSettings';
import { useWecomSyncJob } from './useWecomSyncJob';
import { active } from './wecomModel';
import { useWecomStyles } from './wecomStyles';

// 轮询判据原来由本文件导出，测试与调用方仍从 './Wecom' 引入，这里保持再导出。
export { syncPollInterval } from './wecomModel';

export default function WecomPage() {
  const { styles } = useWecomStyles();
  const {
    form,
    companies,
    companiesLoading,
    companyId,
    selectCompany,
    settings,
    settingsLoading,
    dirty,
    markDirty,
    saveMutation,
  } = useWecomSettings();
  const { job, syncMode, setSyncMode, startMutation, resetStartedJob } = useWecomSyncJob({
    companyId,
    settings,
  });

  const canStart = !!settings?.secretConfigured && !dirty && !active(job);

  // 切公司必须同步清掉任务 id：放到 effect 里会先带着旧 id 渲染一帧并发一次陈旧请求。
  const handleSelectCompany = (value: number) => {
    resetStartedJob();
    selectCompany(value);
  };

  return (
    <PageContainer
      title="企业微信"
      subTitle="按通讯录、企业微信登录、JS-SDK、应用消息的顺序完成审批入口配置"
    >
      <Row gutter={[24, 24]} className={styles.grid}>
        <Col xs={24} xl={10}>
          <WecomConnectionCard
            form={form}
            companies={companies}
            companiesLoading={companiesLoading}
            companyId={companyId}
            settings={settings}
            loading={companiesLoading || settingsLoading}
            dirty={dirty}
            saving={saveMutation.isPending}
            onDirty={markDirty}
            onSelectCompany={handleSelectCompany}
            onSave={(values) => saveMutation.mutate(values)}
          />
        </Col>
        <Col xs={24} xl={14}>
          <WecomSyncCard
            job={job}
            loading={settingsLoading}
            canStart={canStart}
            syncMode={syncMode}
            onChangeMode={setSyncMode}
            starting={startMutation.isPending}
            onStart={() => startMutation.mutate(syncMode)}
          />
        </Col>
      </Row>
      <WecomAccessCard />
      <WecomReadinessCard companyId={companyId} settings={settings} />
    </PageContainer>
  );
}
