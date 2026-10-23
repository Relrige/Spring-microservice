import { describe, expect, it } from 'vitest';
import { IMAGE_URL_PATTERN } from '../features/manage/field-rules';
import { DEMO_PRODUCTS } from './demo-data';

// The illustrations served from public/ (paths relative to the project root).
const FILES = Object.keys(import.meta.glob('/public/images/products/*.svg'));

describe('demo data', () => {
  it("every fixture product's image is a file in public/ that Catalog accepts", () => {
    expect(FILES.length).toBeGreaterThan(0);
    for (const product of DEMO_PRODUCTS) {
      expect(product.image, product.title).toMatch(IMAGE_URL_PATTERN);
      expect(FILES, product.image).toContain(`/public${product.image}`);
    }
  });
});
