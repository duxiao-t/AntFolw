import { act, screen, waitFor } from '@testing-library/react';
import { Toast, unstableSetRender } from 'antd-mobile';
import { describe, expect, it } from 'vitest';

describe('React 19 imperative overlay renderer', () => {
  it('renders, updates, unmounts, and reuses a container with a fresh root', async () => {
    const container = document.createElement('div');
    document.body.append(container);
    const render = unstableSetRender();
    try {
      let unmount: () => void | Promise<void> = () => {};
      await act(async () => { unmount = render(<span>first</span>, container); });
      expect(container).toHaveTextContent('first');
      await act(async () => { unmount = render(<span>updated</span>, container); });
      expect(container).toHaveTextContent('updated');
      await act(async () => { await unmount(); });
      expect(container).toBeEmptyDOMElement();
      await act(async () => { unmount = render(<span>reopened</span>, container); });
      expect(container).toHaveTextContent('reopened');
      await act(async () => { await unmount(); });
      expect(container).toBeEmptyDOMElement();
    } finally {
      container.remove();
    }
  });

  it('shows and clears a real Toast without the legacy unmount API', async () => {
    await act(async () => { Toast.show({ content: 'overlay regression', duration: 0 }); });
    expect(await screen.findByText('overlay regression')).toBeInTheDocument();
    await act(async () => { Toast.clear(); });
    await waitFor(() => expect(screen.queryByText('overlay regression')).not.toBeInTheDocument());
  });
});
