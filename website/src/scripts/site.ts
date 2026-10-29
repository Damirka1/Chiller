const heading = document.querySelector<HTMLElement>('[data-typewriter]');
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
const artwork = document.querySelector<HTMLElement>('.hero-art');
if (artwork) {
  new ResizeObserver(entries => {
    artwork.style.setProperty('--art-size', `${entries[0].contentRect.width}px`);
  }).observe(artwork);
  artwork.addEventListener('pointermove', event => {
    if (reducedMotion.matches || event.pointerType !== 'mouse') return;
    const bounds = artwork.getBoundingClientRect();
    artwork.style.setProperty('--crystal-x', `${-(event.clientY - bounds.top - bounds.height / 2) / bounds.height * 12}deg`);
    artwork.style.setProperty('--crystal-y', `${(event.clientX - bounds.left - bounds.width / 2) / bounds.width * 12}deg`);
  });
  artwork.addEventListener('pointerleave', () => {
    artwork.style.setProperty('--crystal-x', '0deg');
    artwork.style.setProperty('--crystal-y', '0deg');
  });
}
if (heading && !reducedMotion.matches) {
  const lines = [...heading.querySelectorAll<HTMLElement>('.typed-line')];
  const originals = lines.map(line => line.textContent || '');
  let timer: ReturnType<typeof setTimeout>;
  const finish = () => {
    clearTimeout(timer);
    lines.forEach((line, i) => { line.textContent = originals[i]; line.parentElement!.classList.remove('typing'); line.classList.remove('flow-reveal'); });
    heading.classList.remove('is-typing');
  };
  heading.classList.add('is-typing');
  lines.forEach(line => { line.parentElement!.dataset.text = line.textContent || ''; line.textContent = ''; });
  let row = 0, character = 0;
  const tick = () => {
    if (reducedMotion.matches) { finish(); return; }
    const line = lines[row];
    if (row === lines.length - 1) {
      line.textContent = originals[row];
      line.classList.add('flow-reveal');
      timer = setTimeout(finish, 1400);
      return;
    }
    line.parentElement!.classList.add('typing');
    line.textContent = originals[row].slice(0, ++character);
    if (character < originals[row].length) timer = setTimeout(tick, 95);
    else {
      line.parentElement!.classList.remove('typing');
      row++; character = 0;
      if (row < lines.length) timer = setTimeout(tick, row === lines.length - 1 ? 360 : 280);
      else finish();
    }
  };
  timer = setTimeout(tick, 250);
  reducedMotion.addEventListener('change', finish, { once: true });
}

// Keep the original heading in the accessibility tree and reserve its wrapping
// before revealing a visual copy. Each section plays only on its first entry.
const sectionHeadings = [...document.querySelectorAll<HTMLElement>('main h2, main h3')];
if (!reducedMotion.matches && 'IntersectionObserver' in window) {
  const timers = new Map<HTMLElement, ReturnType<typeof setTimeout>>();
  const restore = (heading: HTMLElement) => {
    clearTimeout(timers.get(heading));
    timers.delete(heading);
    heading.querySelector('.section-typed-copy')?.remove();
    heading.classList.remove('section-typewriter', 'section-typing');
  };
  const observer = new IntersectionObserver(entries => {
    entries.forEach(entry => {
      if (!entry.isIntersecting) return;
      const heading = entry.target as HTMLElement;
      observer.unobserve(heading);
      if (reducedMotion.matches) { restore(heading); return; }
      const visual = heading.querySelector<HTMLElement>('.section-typed-copy')!;
      const characters = Array.from(heading.dataset.headingText!);
      let index = 0;
      heading.classList.add('section-typing');
      const type = () => {
        visual.textContent = characters.slice(0, ++index).join('');
        if (index < characters.length) timers.set(heading, setTimeout(type, 45));
        else restore(heading);
      };
      timers.set(heading, setTimeout(type, 120));
    });
  }, { threshold: 0.25 });
  sectionHeadings.forEach(heading => {
    const text = heading.textContent || '';
    heading.dataset.headingText = text;
    const visual = document.createElement('span');
    visual.className = 'section-typed-copy';
    visual.setAttribute('aria-hidden', 'true');
    heading.setAttribute('aria-label', text);
    heading.classList.add('section-typewriter');
    heading.append(visual);
    observer.observe(heading);
  });
  reducedMotion.addEventListener('change', () => {
    observer.disconnect();
    sectionHeadings.forEach(restore);
  }, { once: true });
}

const menu = document.querySelector<HTMLButtonElement>('.menu-button')!;
const mobileNav = document.querySelector<HTMLElement>('#mobile-nav')!;
function closeMenu() { menu.setAttribute('aria-expanded', 'false'); mobileNav.hidden = true; }
menu.addEventListener('click', () => {
  const open = menu.getAttribute('aria-expanded') !== 'true';
  menu.setAttribute('aria-expanded', String(open)); mobileNav.hidden = !open;
});
mobileNav.querySelectorAll('a').forEach(link => link.addEventListener('click', closeMenu));
document.addEventListener('keydown', event => { if (event.key === 'Escape' && !mobileNav.hidden) { closeMenu(); menu.focus(); } });
matchMedia('(min-width: 901px)').addEventListener('change', event => { if (event.matches) closeMenu(); });
document.querySelectorAll<HTMLAnchorElement>('[data-language]').forEach(link => {
  link.addEventListener('click', () => { link.hash = window.location.hash; });
});
const views = document.querySelectorAll<HTMLButtonElement>('[data-view]');
const screens = document.querySelectorAll<HTMLElement>('[data-screen]');
const caption = document.querySelector<HTMLElement>('#view-caption')!;
const captions: string[] = JSON.parse(caption.dataset.captions!);
let selected = 0;
views.forEach((button, index) => button.addEventListener('click', () => {
  selected = index;
  views.forEach((view, i) => view.setAttribute('aria-pressed', String(i === index)));
  screens.forEach((screen, i) => { screen.hidden = i !== index; });
  caption.textContent = captions[index];
  document.querySelector('.demo-caption > span')!.textContent = `0${index + 1} — 03`;
}));
const dialog = document.querySelector<HTMLDialogElement>('.image-dialog')!;
document.querySelector('[data-expand]')!.addEventListener('click', () => {
  const source = screens[selected].querySelector('img')!;
  const target = dialog.querySelector('img')!;
  target.src = source.currentSrc || source.src; target.alt = source.alt;
  dialog.showModal();
});
dialog.querySelector('button')!.addEventListener('click', () => dialog.close());
dialog.addEventListener('click', event => { if (event.target === dialog) dialog.close(); });
