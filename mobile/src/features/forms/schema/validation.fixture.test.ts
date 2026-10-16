import { describe, expect, it } from 'vitest';
import fixture from '../../../../../fixtures/field-semantics/validation.json';
import { validateSchemaValues } from './fieldRegistry';
import type { MobileSchemaNode } from './types';

describe('submission validation shared with desktop and server', () => {
  for (const sample of fixture.cases) {
    it(sample.id, () => {
      expect(Object.keys(validateSchemaValues(sample.schema as MobileSchemaNode[], sample.values)))
        .toHaveLength(sample.valid ? 0 : 1);
    });
  }
});
