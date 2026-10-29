import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

for (const lang of ['en', 'ru']) {
  for (const width of [1440, 768, 390, 320]) {
    test(`${lang} at ${width}px: assets, layout, accessibility`, async ({ page }) => {
      await page.setViewportSize({ width, height: 1000 });
      const errors: string[] = [];
      page.on('pageerror', error => errors.push(error.message));
      page.on('response', response => { if (response.status() >= 400) errors.push(`${response.status()} ${response.url()}`); });
      await page.goto(lang === 'en' ? '/Chiller/' : '/Chiller/ru/');
      await page.evaluate(() => document.fonts.ready);
      await expect(page.locator('html')).toHaveAttribute('lang', lang);
      await expect(page.locator('h1')).toHaveCount(1);
      await page.screenshot({ path: `test-results/${lang}-${width}-initial.png`, fullPage: true });
      const overflow = await page.evaluate(() => [...document.querySelectorAll('body *')].filter(el => el.getBoundingClientRect().right > innerWidth + 1).map(el => `${el.tagName}.${el.className}: ${el.getBoundingClientRect().right}`));
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), overflow.join('\n')).toBe(true);
      // Trigger lazy images and inspect every screenshot, rather than only the hero.
      await page.locator('#workspace').scrollIntoViewIfNeeded();
      for (let index = 0; index < 3; index++) {
        await page.locator(`[data-view="${index}"]`).click();
        await expect(page.locator(`[data-view="${index}"]`)).toHaveAttribute('aria-pressed', 'true');
        await expect.poll(() => page.locator(`[data-screen="${index}"] img`).evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0)).toBe(true);
      }
      await page.locator('[data-expand]').click();
      await expect(page.locator('dialog')).toBeVisible();
      await page.keyboard.press('Escape');
      await expect(page.locator('dialog')).not.toBeVisible();
      await page.locator('[data-view="0"]').click();
      await page.locator('.faq summary').first().click();
      await expect(page.locator('.faq details').first()).toHaveAttribute('open', '');
      const scan = await new AxeBuilder({ page }).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();
      expect(scan.violations.map(v => ({ id: v.id, nodes: v.nodes.map(n => ({ target: n.target, summary: n.failureSummary })) }))).toEqual([]);
      await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }));
      await page.screenshot({ path: `test-results/${lang}-${width}.png`, fullPage: true });
      expect(errors).toEqual([]);
    });
  }
}

test('language navigation keeps the section and mobile menu is keyboard operable', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/Chiller/#workspace');
  await page.locator('[data-language][lang="ru"]').click();
  await expect(page).toHaveURL(/\/Chiller\/ru\/#workspace$/);
  await page.locator('.menu-button').click();
  await expect(page.locator('#mobile-nav')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.locator('#mobile-nav')).not.toBeVisible();
  await expect(page.locator('.menu-button')).toBeFocused();
  await page.locator('.menu-button').click();
  await page.locator('#mobile-nav a[href="#start"]').click();
  await expect(page.locator('#mobile-nav')).not.toBeVisible();
  await expect(page).toHaveURL(/#start$/);
});

test('reduced motion and no-JavaScript reading', async ({ browser }) => {
  const page = await browser.newPage({ javaScriptEnabled: false, reducedMotion: 'reduce' });
  await page.goto('/Chiller/');
  await expect(page.locator('h1')).toBeVisible();
  await expect(page.locator('.hero-buttons a').first()).toHaveAttribute('href', 'https://github.com/Damirka1/Chiller/releases');
  await expect(page.locator('a[hreflang="ru"]').last()).toHaveAttribute('href', '/Chiller/ru/');
  await expect(page.locator('.crystal-wrap')).toHaveCSS('animation-name', 'none');
  await page.close();
});
