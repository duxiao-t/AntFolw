import { fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { Inspector } from './Inspector';
import { useFormDesignerStore } from './useFormDesignerStore';

beforeEach(() => {
  useFormDesignerStore.setState({
    schema: [{
      id: 'kind',
      type: 'select',
      label: '类型',
      props: {
        showSearch: true,
        options: [{ id: 'a', label: '甲', value: 'a' }],
      },
    }],
    selectedId: 'kind',
    history: { past: [], future: [] },
  });
});

describe('select display style inspector', () => {
  it('defaults to dropdown and writes all four styles without clearing search', () => {
    render(<Inspector />);

    const moduleLabels = [
      '基础设置',
      '组件设置',
      '展示样式',
      '逻辑规则',
      '校验规则',
      '显示逻辑',
    ];
    const moduleHeaders = moduleLabels.map((label) =>
      screen.getByRole('button', { name: new RegExp(`${label}$`) }),
    );
    expect(
      moduleHeaders.slice(0, -1).every((element, index) =>
        Boolean(
          element.compareDocumentPosition(moduleHeaders[index + 1] as Node) &
            Node.DOCUMENT_POSITION_FOLLOWING,
        ),
      ),
    ).toBe(true);
    expect(screen.getAllByText('展示样式')).toHaveLength(1);
    expect(screen.getAllByText('逻辑规则')).toHaveLength(1);
    expect(screen.getByText('启用逻辑规则')).toBeInTheDocument();
    expect(document.querySelector('.anticon-unordered-list')).toBeInTheDocument();
    expect(document.querySelector('.anticon-down-circle')).toBeInTheDocument();
    expect(document.querySelector('.anticon-credit-card')).toBeInTheDocument();
    expect(document.querySelector('.anticon-appstore')).toBeInTheDocument();

    const componentPanel = screen
      .getByRole('button', { name: /组件设置$/ })
      .closest('.ant-collapse-item') as HTMLElement;
    const displayPanel = screen
      .getByRole('button', { name: /展示样式$/ })
      .closest('.ant-collapse-item') as HTMLElement;
    const search = within(componentPanel).getByRole('checkbox', { name: '支持搜索' });
    expect(within(componentPanel).getByRole('checkbox', { name: '允许清空' })).toBeChecked();
    expect(within(displayPanel).queryByRole('checkbox', { name: '允许清空' })).not.toBeInTheDocument();
    expect(within(displayPanel).queryByRole('checkbox', { name: '支持搜索' })).not.toBeInTheDocument();
    expect(screen.getByRole('radio', { name: '下拉选择' })).toHaveAttribute('aria-checked', 'true');
    expect(search).toBeEnabled();

    for (const [label, value] of [
      ['列表', 'list'],
      ['块状（单列）', 'block_single'],
      ['块状（双列）', 'block_double'],
      ['下拉选择', 'dropdown'],
    ]) {
      fireEvent.click(screen.getByRole('radio', { name: label }));
      expect(useFormDesignerStore.getState().schema[0]?.props?.displayStyle).toBe(value);
      if (value === 'dropdown') {
        expect(search).toBeEnabled();
      } else {
        expect(search).toBeDisabled();
      }
    }

    expect(search).toBeChecked();
    expect(search).toBeEnabled();
  });
});

describe('checklist result inspector', () => {
  it('uses result names as mobile labels and assigns colors to new results', () => {
    useFormDesignerStore.setState({
      schema: [{
        id: 'check',
        type: 'checklist',
        label: '设备检查',
        props: {
          items: [{ id: 'item-1', label: '设备外观', required: true }],
          results: [
            { id: 'ok', label: '合格', color: '#123456' },
            { id: 'fix', label: '需整改', color: '#D93025' },
          ],
        },
      }],
      selectedId: 'check',
      history: { past: [], future: [] },
    });
    render(<Inspector />);

    expect(screen.getByText(/结果名称同时作为手机按钮文字/)).toBeInTheDocument();
    expect(document.querySelector('[aria-label="结果1颜色"]')).toBeInTheDocument();

    fireEvent.change(screen.getByDisplayValue('合格'), { target: { value: '正常' } });
    fireEvent.click(screen.getByRole('button', { name: /新增结果$/ }));

    const results = useFormDesignerStore.getState().schema[0]?.props?.results as Array<{
      label: string;
      color: string;
    }>;
    expect(results[0]).toMatchObject({ label: '正常', color: '#123456' });
    expect(results[2]).toMatchObject({ label: '结果3', color: '#8F8F8F' });
  });
});
