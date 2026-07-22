import { Link, useNavigate, useParams } from 'react-router-dom';
import { Button } from 'antd-mobile';
import { AppPage } from '../../shared/ui/AppPage';

const actionStyle: React.CSSProperties = {
  display: 'grid',
  gap: 8,
  marginTop: 16,
};

const linkButtonStyle: React.CSSProperties = {
  minHeight: 44,
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  borderRadius: 'var(--af-radius-control)',
  background: 'var(--af-color-primary)',
  color: 'var(--af-color-on-primary)',
  textDecoration: 'none',
};

export function SubmitSuccessPage() {
  const { instanceId = '' } = useParams();
  const navigate = useNavigate();

  return (
    <AppPage title="提交成功">
      <p>流程已发起。</p>
      <div style={actionStyle}>
        <Link to={`/processes/${instanceId}`} style={linkButtonStyle}>
          查看详情
        </Link>
        <Button block onClick={() => navigate('/workbench')}>
          返回工作台
        </Button>
      </div>
    </AppPage>
  );
}

export default SubmitSuccessPage;
