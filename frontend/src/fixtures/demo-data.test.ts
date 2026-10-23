import { describe, expect, it } from 'vitest';
import credits from '../../docs/image-credits.md?raw';
import { IMAGE_URL_PATTERN } from '../features/manage/field-rules';
import { DEMO_PRODUCTS } from './demo-data';

describe('demo data', () => {
  it("every fixture product's image is a Commons photo that Catalog accepts, with a credit", () => {
    for (const product of DEMO_PRODUCTS) {
      expect(product.image, product.title).toMatch(IMAGE_URL_PATTERN);
      expect(product.image, product.title).toMatch(/^https:\/\/thumb\.wikimedia\.org\//);
      expect(credits, product.title).toContain(`| ${product.title} |`);
    }
  });
});
