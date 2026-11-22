import { describe, expect, it } from 'vitest';
import { EMAIL } from './RegisterPage';

// The earlier pattern, kept here to prove the backtracking-free rewrite accepts the same strings.
const PREVIOUS = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

describe('EMAIL pattern', () => {
  it('accepts exactly what the previous pattern accepted', () => {
    const samples = [
      'a@b.c',
      'olena@example.com',
      'a@.b.c',
      'a@b..c',
      'a@b.',
      'a@.b',
      'a@b',
      'a@..',
      'a@b.c.',
      '@b.c',
      'a b@c.d',
      'a@b@c.d',
      'a@b.c d',
      '',
      'a@...c',
      'x@y.z.w',
    ];
    const alphabet = ['a', '.', '@', ' '];
    for (let n = 0; n <= 7; n += 1) {
      for (let i = 0; i < alphabet.length ** n; i += 1) {
        let s = '';
        for (let k = 0, v = i; k < n; k += 1, v = Math.floor(v / alphabet.length)) {
          s += alphabet[v % alphabet.length];
        }
        samples.push(s);
      }
    }
    for (const s of samples) expect(EMAIL.test(s), s).toBe(PREVIOUS.test(s));
  });
});
