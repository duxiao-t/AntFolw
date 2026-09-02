import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { Form } from 'antd';
import { describe, expect, it } from 'vitest';
import BusinessNumberEditor from './BusinessNumberEditor';

describe('BusinessNumberEditor', () => {
  it('keeps long status text outside the switch and reveals settings when enabled', async () => {
    render(
      <Form initialValues={{ businessNumber: { enabled: false } }}>
        <BusinessNumberEditor fields={[]} />
      </Form>,
    );

    const toggle = screen.getByRole('switch', { name: '自定义流水号' });
    expect(toggle).not.toBeChecked();
    expect(screen.getByText('使用默认编号')).toBeInTheDocument();
    expect(screen.queryByLabelText('全系统唯一前缀')).not.toBeInTheDocument();

    fireEvent.click(toggle);

    await waitFor(() => {
      expect(toggle).toBeChecked();
      expect(screen.getByText('已启用')).toBeInTheDocument();
      expect(screen.getByLabelText('全系统唯一前缀')).toBeInTheDocument();
    });
  });
});
