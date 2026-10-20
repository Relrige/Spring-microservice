// Mantine theme generated from the design tokens (design D16).
// docs/design/design-system/tokens.json is the single source: never copy values by hand.
import { createTheme, type CSSVariablesResolver, type MantineColorsTuple } from '@mantine/core';
import tokens from '../../docs/design/design-system/tokens.json';

type Scheme = 'light' | 'dark';
type TokenValue = string | { light: string; dark: string };
interface Token {
  name: string;
  value: TokenValue;
}

const colorList = tokens.color.tokens as Token[];

function byName(list: Token[]): Map<string, TokenValue> {
  return new Map(list.map((token) => [token.name, token.value]));
}

const colorValues = byName(colorList);

/** The value of a colour token in one scheme, with `{alias}` references resolved. */
export function colorToken(name: string, scheme: Scheme): string {
  const value = colorValues.get(name);
  if (value === undefined) throw new Error(`Unknown colour token: ${name}`);
  const raw = typeof value === 'string' ? value : value[scheme];
  const alias = /^\{(.+)\}$/.exec(raw);
  return alias?.[1] ? colorToken(alias[1], scheme) : raw;
}

function sizeToken(list: Token[], name: string): string {
  const value = byName(list).get(name);
  if (typeof value !== 'string') throw new Error(`Unknown token: ${name}`);
  return value;
}

function schemeToken(list: Token[], name: string, scheme: Scheme): string {
  const value = byName(list).get(name);
  if (value === undefined) throw new Error(`Unknown token: ${name}`);
  return typeof value === 'string' ? value : value[scheme];
}

const space = (name: string) => sizeToken(tokens.spacing.tokens, name);
const radius = (name: string) => sizeToken(tokens.radius.tokens, name);
export const layoutToken = (name: string) => sizeToken(tokens.layout.tokens, name);

const typeStyles = new Map(
  tokens.type.groups.flatMap((group) => group.styles.map((style) => [style.name, style] as const)),
);
function typeStyle(name: string) {
  const style = typeStyles.get(name);
  if (!style) throw new Error(`Unknown type style: ${name}`);
  return style;
}

/** `--vs-<token>` custom properties for one colour scheme, plus Mantine's own variables mapped onto them. */
export function schemeVariables(scheme: Scheme): Record<`--${string}`, string> {
  const vars: Record<`--${string}`, string> = {};
  for (const token of colorList) vars[`--vs-${token.name}`] = colorToken(token.name, scheme);
  for (const token of tokens.shadow.tokens as Token[]) {
    vars[`--vs-${token.name}`] = schemeToken(tokens.shadow.tokens as Token[], token.name, scheme);
  }
  Object.assign(vars, {
    '--mantine-color-body': 'var(--vs-surface)',
    '--mantine-color-text': 'var(--vs-ink)',
    '--mantine-color-dimmed': 'var(--vs-ink-muted)',
    '--mantine-color-placeholder': 'var(--vs-ink-muted)',
    '--mantine-color-anchor': 'var(--vs-primary)',
    '--mantine-color-error': 'var(--vs-danger)',
    '--mantine-color-default': 'var(--vs-surface-raised)',
    '--mantine-color-default-hover': 'var(--vs-surface-sunken)',
    '--mantine-color-default-color': 'var(--vs-ink)',
    '--mantine-color-default-border': 'var(--vs-line-strong)',
    '--mantine-primary-color-contrast': 'var(--vs-on-primary)',
  });
  return vars;
}

export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {
    '--vs-font-mono': tokens.type.families.mono,
    '--vs-content-max': layoutToken('content-max'),
    '--vs-header-height': layoutToken('header-height'),
    '--vs-filter-panel': layoutToken('filter-panel'),
  },
  light: schemeVariables('light'),
  dark: schemeVariables('dark'),
});

// Shade 6 is primary in light theme, shade 4 primary in dark theme (design-system README).
const electric: MantineColorsTuple = [
  '#eef3ff',
  '#dce6ff',
  '#b9ccff',
  '#93b0ff',
  colorToken('primary', 'dark'),
  '#4a78f0',
  colorToken('primary', 'light'),
  '#1843b3',
  '#123489',
  '#0c2560',
];

const heading = (name: string) => {
  const style = typeStyle(name);
  return {
    fontSize: style.fontSize,
    lineHeight: style.lineHeight,
    fontWeight: String(style.fontWeight),
  };
};

export const theme = createTheme({
  primaryColor: 'electric',
  primaryShade: { light: 6, dark: 4 },
  colors: { electric },
  fontFamily: tokens.type.families.sans,
  fontFamilyMonospace: tokens.type.families.mono,
  headings: {
    fontFamily: tokens.type.families.sans,
    fontWeight: '650',
    sizes: {
      h1: heading('heading-1'),
      h2: heading('heading-2'),
      h3: heading('heading-3'),
    },
  },
  fontSizes: {
    xs: typeStyle('label').fontSize,
    sm: typeStyle('small').fontSize,
    md: typeStyle('body').fontSize,
    lg: typeStyle('heading-3').fontSize,
    xl: typeStyle('heading-2').fontSize,
  },
  lineHeights: {
    xs: typeStyle('label').lineHeight,
    sm: typeStyle('small').lineHeight,
    md: typeStyle('body').lineHeight,
    lg: typeStyle('heading-3').lineHeight,
    xl: typeStyle('heading-2').lineHeight,
  },
  spacing: {
    xs: space('space-1'),
    sm: space('space-2'),
    md: space('space-4'),
    lg: space('space-6'),
    xl: space('space-8'),
  },
  radius: {
    xs: radius('radius-sm'),
    sm: radius('radius-sm'),
    md: radius('radius-md'),
    lg: radius('radius-lg'),
    xl: radius('radius-lg'),
  },
  defaultRadius: 'md',
  focusRing: 'auto',
  components: {
    Button: { defaultProps: { radius: 'sm' } },
    ActionIcon: { defaultProps: { radius: 'sm' } },
    Input: {
      defaultProps: { radius: 'sm' },
      styles: {
        input: {
          backgroundColor: 'var(--vs-surface-raised)',
          borderColor: 'var(--vs-line-strong)',
          color: 'var(--vs-ink)',
        },
      },
    },
    Badge: { defaultProps: { radius: 'sm' } },
    Pagination: { defaultProps: { radius: 'sm' } },
  },
});
