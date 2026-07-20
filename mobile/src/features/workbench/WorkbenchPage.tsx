import { useMemo } from 'react';
import classes from './workbench.module.css';
import { AppGrid } from './components/AppGrid';
import { RecentProcesses } from './components/RecentProcesses';
import {
  capFavorites,
  capRecents,
  useMobileBootstrap,
} from './workbench.api';
import { PageEmpty, PageError, PageSkeleton } from '../../shared/ui/PageStates';

function greeting(now: Date): string {
  const hour = now.getHours();
  if (hour < 6) return '夜深了';
  if (hour < 11) return '早上好';
  if (hour < 13) return '中午好';
  if (hour < 18) return '下午好';
  return '晚上好';
}

export function WorkbenchPage() {
  const query = useMobileBootstrap();
  const apps = useMemo(() => capFavorites(query.data?.favoriteApps ?? []), [query.data]);
  const processes = useMemo(() => capRecents(query.data?.recentProcesses ?? []), [query.data]);
  const userName = query.data?.user.displayName ?? '';

  if (query.isPending) {
    return <PageSkeleton rows={5} />;
  }

  if (query.isError) {
    return (
      <PageError
        title="工作台加载失败"
        message="请稍后重试。"
        onRetry={() => {
          void query.refetch();
        }}
      />
    );
  }

  return (
    <main className={classes.workbench} data-testid="workbench">
      <header className={classes.header}>
        <h1 className={classes.title}>{greeting(new Date())}{userName ? `，${userName}` : ''}</h1>
      </header>
      <section className={classes.section} aria-label="常用应用">
        <header className={classes.sectionHeader}>
          <h2 className={classes.sectionTitle}>常用应用</h2>
          <p className={classes.sectionMeta}>最多 8 个</p>
        </header>
        {apps.length === 0 ? (
          <PageEmpty title="还没有常用应用" hint="去应用目录添加几个吧" />
        ) : (
          <AppGrid apps={apps} />
        )}
      </section>
      <section className={classes.section} aria-label="最近流程">
        <header className={classes.sectionHeader}>
          <h2 className={classes.sectionTitle}>最近流程</h2>
          <p className={classes.sectionMeta}>最多 3 条</p>
        </header>
        {processes.length === 0 ? (
          <PageEmpty title="还没有最近的流程" hint="发起一份审批看看" />
        ) : (
          <RecentProcesses processes={processes} />
        )}
      </section>
    </main>
  );
}

export default WorkbenchPage;
