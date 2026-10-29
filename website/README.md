# Chiller website

**English** · [Русский](README.ru.md)

Static bilingual landing page built with Astro, Tailwind CSS and TypeScript. English is the default at `/Chiller/`; Russian is at `/Chiller/ru/`. No backend, analytics, remote fonts or model requests. Screenshots currently show the Russian interface, independently of the site language.

## Local development

Use Node 24 (tested with 24.14.1) and npm:

```sh
cd website
npm ci
npm run dev
```

Open the printed URL with `/Chiller/`. Build and inspect the production version with `npm run build` and `npm run preview`. Do not open `dist/index.html` directly: GitHub Pages base paths require an HTTP server.

## Verification

```sh
npm run build
npm test
```

Local browser tests use installed Google Chrome. CI uses Playwright Chromium (`npx playwright install --with-deps chromium`, then `CI=true npm test`). Tests cover both languages at 320, 390, 768 and 1440 px, screenshot switching, the preview dialog, mobile navigation, language links, image loading, browser errors, reduced motion and automated WCAG checks. Full-page captures go to `test-results/`. Automated checks do not replace manual screen-reader testing.

## GitHub Pages

The prepared workflow is `.github/workflows/website.yml`. It runs **manually only**, and publishes only when the `publish` input is selected on `master`. It builds and tests before deployment. No deployment or repository setting change is performed by creating these files.

1. Merge the website and workflow into `master`.
2. In repository Settings → Pages, choose **GitHub Actions** as the source.
3. Run **Website · GitHub Pages** from Actions with `publish` checked.

Expected URL after a successful deployment: `https://Damirka1.github.io/Chiller/`. This is a configured target, not proof that a site is live. `astro.config.mjs` owns the site URL and base path. If these change, update the font URLs, browser tests and this guide too.

## Editing

- `src/content.ts`: English and Russian copy; update together.
- `src/components/Landing.astro`: shared semantic layout and metadata.
- `src/styles/global.css`: design tokens, responsive styling and reduced motion.
- `src/scripts/site.ts`: navigation, screenshot selector and accessible native dialog.
- `src/assets/`: screenshots copied from `docs/images` and the approved Chiller app icon. Replace with new app captures as needed. Astro creates optimized WebP assets during the build.
- `public/fonts/`: self-hosted Medeo fonts with the SIL OFL license.

Product claims are based on the project README. Demo images are identified as scripted. Download links go to GitHub Releases, without assuming an installer filename or a latest version.

## Dependencies and licenses

Exact versions are pinned in `package.json` and `package-lock.json`: Astro 7.4.4 (MIT), Tailwind CSS and its Vite plugin 4.3.3 (MIT), Playwright Test 1.64.1 (Apache-2.0), axe-core Playwright integration 4.14.0 (MPL-2.0; development only). Medeo is SIL OFL 1.1; see `public/fonts/OFL.txt`. Website source follows the repository Apache-2.0 license. No additional stock artwork or third-party brand logos are included.
