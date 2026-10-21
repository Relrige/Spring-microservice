import { describe, expect, it } from 'vitest';
import { colorToken, schemeVariables, theme } from './theme';

describe('theme from tokens.json', () => {
  it('exposes every colour token for both schemes', () => {
    expect(schemeVariables('light')['--vs-surface']).toBe('#f5f7fa');
    expect(schemeVariables('dark')['--vs-surface']).toBe('#0e1219');
    expect(schemeVariables('dark')['--vs-on-volt']).toBe('#111723');
  });

  it('resolves aliases such as link → primary', () => {
    expect(colorToken('link', 'light')).toBe('#1f55e0');
    expect(colorToken('focus-ring', 'dark')).toBe('#6f9bff');
  });

  it('maps primary to shade 6 (light) and shade 4 (dark)', () => {
    expect(theme.colors?.electric?.[6]).toBe('#1f55e0');
    expect(theme.colors?.electric?.[4]).toBe('#6f9bff');
    expect(theme.primaryShade).toEqual({ light: 6, dark: 4 });
  });

  it('uses Inter and the token radius and spacing', () => {
    expect(theme.fontFamily).toMatch(/^Inter/);
    expect(theme.radius?.md).toBe('10px');
    expect(theme.spacing?.lg).toBe('24px');
  });
});
