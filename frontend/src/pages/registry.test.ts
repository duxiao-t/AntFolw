import { describe, expect, it } from 'vitest';
import { PAGE_CAPABILITIES, PAGES } from './registry';

describe('page capability registry', () => {
  it('covers every menu page exactly once', () => {
    expect(Object.keys(PAGE_CAPABILITIES).sort()).toEqual(PAGES.map((page) => page.key).sort());
    PAGES.forEach((page) => {
      expect(page.readCapabilities).toEqual(PAGE_CAPABILITIES[page.key]);
    });
  });
});
