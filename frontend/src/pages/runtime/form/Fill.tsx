import { Button, Card, message } from 'antd';
import { useState } from 'react';
import { useParams, history } from '@umijs/max';
import { useQuery, useMutation } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { FormRenderer } from '../../../components/FormRenderer/FormRenderer';

export default function Fill() {
  const params = useParams();
  const code = params.code as string;
  const [val, setVal] = useState<any>({});
  const { data: fd, isFetching } = useQuery({
    queryKey: ['form-def', code],
    queryFn: () => request(`/api/forms/definitions/by-code/${code}`),
  });
  const startInstance = useMutation({
    mutationFn: () =>
      request('/api/instances/start', {
        method: 'POST',
        data: { formCode: code, data: val },
      }),
    onSuccess: () => {
      message.success('提交成功');
      history.push('/proc');
    },
  });

  if (isFetching) return <Card loading />;
  if (!fd) return <Card>表单未找到</Card>;
  return (
    <Card title={fd.name}>
      <FormRenderer
        schema={fd.schema ?? []}
        mode="runtime-fill"
        value={val}
        onChange={setVal}
      />
      <Button
        type="primary"
        style={{ marginTop: 16 }}
        onClick={() => startInstance.mutate()}
        loading={startInstance.isPending}
      >
        提交并发起审批
      </Button>
    </Card>
  );
}
