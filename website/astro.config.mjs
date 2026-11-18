import { defineConfig } from 'astro/config';
import tailwindcss from '@tailwindcss/vite';

const selfHosted = process.env.CHILLER_SELF_HOSTED === '1';

export default defineConfig({
  site: selfHosted ? 'https://chiller.damirka.space' : 'https://Damirka1.github.io',
  base: selfHosted ? '/' : '/Chiller',
  trailingSlash: 'always',
  output: 'static',
  // The screenshots live in ../docs/images, shared with the README; the dev server may read them.
  vite: { plugins: [tailwindcss()], server: { fs: { allow: ['..'] } } },
});
