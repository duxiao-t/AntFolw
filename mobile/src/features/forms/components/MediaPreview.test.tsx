import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  isImageFile,
  isVideoFile,
  ReadonlyMediaList,
} from './MediaPreview';

const IMAGE_FILE = {
  id: 'p1',
  name: 'a.png',
  contentType: 'image/png',
  contentUrl: '/api/mobile/files/p1/content',
};
const VIDEO_FILE = {
  id: 'v1',
  name: 'b.mp4',
  contentType: 'video/mp4',
  contentUrl: '/api/mobile/files/v1/content',
};
const PDF_FILE = {
  id: 'd1',
  name: 'c.pdf',
  contentType: 'application/pdf',
  contentUrl: '/api/mobile/files/d1/content',
};

function fetchMock() {
  return globalThis.fetch as ReturnType<typeof vi.fn>;
}

beforeEach(() => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('broken')) {
        return new Response(
          JSON.stringify({ code: 'FILE_STORAGE_FAILED', message: 'missing' }),
          { status: 422, headers: { 'content-type': 'application/json' } },
        );
      }
      return new Response(new Blob(['blob']), { status: 200 });
    }),
  );
  vi.stubGlobal('URL', {
    ...URL,
    createObjectURL: vi.fn(() => 'blob:preview'),
    revokeObjectURL: vi.fn(),
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('media file helpers', () => {
  it('detects image and video by content type', () => {
    expect(isImageFile(IMAGE_FILE)).toBe(true);
    expect(isVideoFile(VIDEO_FILE)).toBe(true);
    expect(isImageFile(PDF_FILE)).toBe(false);
    expect(isVideoFile(PDF_FILE)).toBe(false);
  });
});

describe('ReadonlyMediaList', () => {
  it('renders image thumbnails without upload progress bars', async () => {
    const { container } = render(<ReadonlyMediaList files={[IMAGE_FILE]} />);

    expect(await screen.findByRole('img', { name: 'a.png' })).toBeInTheDocument();
    expect(container.querySelector('.af-upload-list__progress')).toBeNull();
    expect(fetchMock()).toHaveBeenCalledWith(
      '/api/mobile/files/p1/content',
      expect.anything(),
    );
  });

  it('plays video after clicking without preloading the blob', async () => {
    const { container } = render(<ReadonlyMediaList files={[VIDEO_FILE]} />);
    fetchMock().mockClear();

    const playButton = screen.getByRole('button', { name: '播放 b.mp4' });
    expect(fetchMock()).not.toHaveBeenCalled();

    await userEvent.click(playButton);

    await waitFor(() => expect(container.querySelector('video')).toBeTruthy());
    expect(fetchMock()).toHaveBeenCalledWith(
      '/api/mobile/files/v1/content',
      expect.anything(),
    );
  });

  it('keeps non-media files as plain name summaries', () => {
    render(<ReadonlyMediaList files={[PDF_FILE]} />);

    expect(screen.getByText('c.pdf')).toBeInTheDocument();
  });
});
