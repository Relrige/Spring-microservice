import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { App } from './App';

describe('App smoke test', () => {
  it('renders the VoltStore wordmark', () => {
    render(<App />);
    expect(screen.getByText('VoltStore')).toBeInTheDocument();
  });

  it('switches between light and dark with the colour-scheme toggle', async () => {
    render(<App />);
    const root = document.documentElement;
    expect(root).toHaveAttribute('data-mantine-color-scheme', 'light');
    await userEvent.click(screen.getByRole('button', { name: 'Switch to dark theme' }));
    expect(root).toHaveAttribute('data-mantine-color-scheme', 'dark');
    await userEvent.click(screen.getByRole('button', { name: 'Switch to light theme' }));
    expect(root).toHaveAttribute('data-mantine-color-scheme', 'light');
  });
});
