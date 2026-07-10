import { test, expect } from '@playwright/test';

test('loads the notes home screen', async ({ page }) => {
  await page.goto('/');
  await expect(page).toHaveTitle(/Notelikeus/i);
  await expect(page.getByRole('searchbox', { name: 'Search notes' })).toBeVisible({
    timeout: 20_000,
  });
  await expect(page.getByText('Notes you add appear here')).toBeVisible();
});

test('can open a new note editor on desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto('/');
  await page.getByRole('button', { name: 'New note' }).click({ timeout: 20_000 });
  await expect(page.getByPlaceholder('Title')).toBeVisible({ timeout: 10_000 });
});

test('can open a new note editor on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  await page.getByRole('button', { name: 'Add note' }).click({ timeout: 20_000 });
  await expect(page.getByPlaceholder('Title')).toBeVisible({ timeout: 10_000 });
});
