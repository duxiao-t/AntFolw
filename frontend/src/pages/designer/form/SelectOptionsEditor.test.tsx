import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { SelectOptionsEditor } from './SelectOptionsEditor';

describe('SelectOptionsEditor', () => {
  it('sets defaults, hides options, and creates the other option', () => {
    const onChange = vi.fn();
    const onDefaultChange = vi.fn();
    render(
      <SelectOptionsEditor
        value={[{ id: 'a', label: '铁面', value: 'iron' }]}
        onChange={onChange}
        onDefaultChange={onDefaultChange}
        onEnableColorsChange={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByLabelText('将铁面设为默认'));
    expect(onDefaultChange).toHaveBeenCalledWith('iron');

    fireEvent.click(screen.getByLabelText('隐藏铁面'));
    expect(onChange).toHaveBeenCalledWith([
      expect.objectContaining({ label: '铁面', value: 'iron', hidden: true }),
    ]);

    fireEvent.click(screen.getByRole('button', { name: '添加其他项' }));
    expect(onChange).toHaveBeenLastCalledWith([
      expect.objectContaining({ label: '铁面', value: 'iron' }),
      expect.objectContaining({ label: '其他', isOther: true }),
    ]);
  });

  it('persists the color toggle and assigns a default swatch', () => {
    const onEnableColorsChange = vi.fn();
    render(
      <SelectOptionsEditor
        value={[{ id: 'a', label: '加工中心', value: 'center' }]}
        onChange={vi.fn()}
        onDefaultChange={vi.fn()}
        onEnableColorsChange={onEnableColorsChange}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '启用选项颜色' }));
    expect(onEnableColorsChange).toHaveBeenCalledWith(true, [
      expect.objectContaining({ color: '#12B76A' }),
    ]);
  });
});

