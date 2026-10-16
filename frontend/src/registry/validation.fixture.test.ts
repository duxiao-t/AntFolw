import { describe, expect, it } from 'vitest';
import fixture from '../../../fixtures/field-semantics/validation.json';
import { firstVisibleValidationError } from './displayConditions';
import type { SchemaNode } from './types';

describe('submission validation shared with mobile and server', () => {
  for (const sample of fixture.cases) {
    it(sample.id, () => {
      expect(firstVisibleValidationError(sample.schema as SchemaNode[], sample.values) == null)
        .toBe(sample.valid);
    });
  }
});
