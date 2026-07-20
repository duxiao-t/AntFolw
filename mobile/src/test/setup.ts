import '@testing-library/jest-dom/vitest';
import 'antd-mobile/es/global';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

afterEach(() => {
  cleanup();
  localStorage.clear();
  sessionStorage.clear();
});
