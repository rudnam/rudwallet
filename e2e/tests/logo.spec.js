// The logo on the introduction page, the sign-in page and in the app is
// public/favicon.svg itself, the same drawing as the app icon.
const { test, expect, signUp } = require('./fixtures');

/** Every logo matching the selector shows, and has loaded the icon. */
async function expectLogos(page, selector) {
  const logos = page.locator(selector);
  await expect(logos.first()).toBeVisible();
  for (const logo of await logos.all()) {
    await expect(logo).toHaveAttribute('src', '/favicon.svg');
    await expect.poll(() => logo.evaluate((img) => img.complete && img.naturalWidth > 0)).toBe(true);
  }
}

for (const [name, viewport] of [
  ['phone', { width: 390, height: 844 }],
  ['desktop', { width: 1280, height: 800 }],
]) {
  test(`the logo is the app icon, at ${name} width`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const shot = (label) => page.screenshot({ path: test.info().outputPath(`${name}-logo-${label}.png`) });

    await page.goto('/');
    await expectLogos(page, '.landing-brand .logo');
    await shot('landing');

    await page.goto('/app/');
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
    await expectLogos(page, 'img.logo');
    await shot('sign-in');

    await signUp(page);
    await shot('app');
  });
}
