import { defineConfig } from 'astro/config';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  site: 'https://Damirka1.github.io',
  base: '/Chiller',
  trailingSlash: 'always',
  output: 'static',
  // The screenshots live in ../docs/images, shared with the README; the dev server may read them.
  vite: { plugins: [tailwindcss()], server: { fs: { allow: ['..'] } } },
});
