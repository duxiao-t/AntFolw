import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { Inspector } from './Inspector';
import { useFormDesignerStore } from './useFormDesignerStore';

/**
 * Inspector 分派到注册表 ConfigPanel，选择字段共用选项编辑器。
 * 改标签必须保留原值及未知属性，保存并重新打开后结果不变。
 */
beforeEach(() => {
  useFormDesignerStore.setState({
    schema: [
      {
        id: 'pick',
        type: 'radio',
        label: '单选',
        props: {
          options: [
            { id: 'a', label: '甲', value: 'a' },
            { id: 'b', label: '乙', value: 'b' },
          ],
        },
      },
    ],
    selectedId: 'pick',
    history: { past: [], future: [] },
  });
});

const optionsOf = () => useFormDesignerStore.getState().schema[0].props?.options ?? [];

describe('radio/checkbox options wiring', () => {
  it('edits an option, persists it to the schema, and still shows it after reopening', () => {
    const { unmount } = render(<Inspector />);

    fireEvent.change(screen.getByLabelText('选项甲'), { target: { value: '甲改' } });

    const options = optionsOf();
    expect(options[0].label).toBe('甲改');
    // 值没被顺手改掉：改标签不该动 value。
    expect(options[0].value).toBe('a');

    // 「重新打开」：从同一份 schema 重新挂载，编辑过的标签还在。
    unmount();
    render(<Inspector />);
    expect(screen.getByLabelText('选项甲改')).toBeInTheDocument();
  });

  it('does not stringify a numeric option value', () => {
    useFormDesignerStore.setState({
      schema: [
        {
          id: 'pick',
          type: 'checkbox',
          label: '多选',
          props: { options: [{ id: 'a', label: '零', value: 0 }] },
        },
      ],
      selectedId: 'pick',
      history: { past: [], future: [] },
    });

    render(<Inspector />);
    fireEvent.change(screen.getByLabelText('选项零'), { target: { value: '零改' } });

    const options = optionsOf();
    expect(options[0].label).toBe('零改');
    expect(options[0].value).toBe(0);
  });
});
