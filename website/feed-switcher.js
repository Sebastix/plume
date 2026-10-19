/* Shared static screenshot demo for the homepage and blog. */
(() => {
  'use strict';
  let instance = 0;
  const screens = [
    { label: 'Search', file: 'search', icon: 'search' },
    { label: 'Notifications', file: 'notifications', icon: 'bell' },
    { label: 'Home', file: 'home', icon: 'posts' },
    { label: 'Photos', file: 'photos', icon: 'photo', shared: true },
    { label: 'Videos', file: 'video-fullscreen', icon: 'play', shared: true },
    { label: 'Articles', file: 'articles', icon: 'article' },
    { label: 'Replies', file: 'reply', icon: 'reply' },
    { label: 'Podcasts', file: 'podcasts', icon: 'podcast' },
    { label: 'Artist gallery', file: 'profile-photos', icon: 'profile' }
  ];
  const symbols = "<svg class=\"symbols\" xmlns=\"http://www.w3.org/2000/svg\" aria-hidden=\"true\"><defs><symbol id=\"i-search\" viewBox=\"0 0 24 24\"><circle cx=\"10.5\" cy=\"10.5\" r=\"6.5\"/><path d=\"m16 16 5 5\"/></symbol><symbol id=\"i-bell\" viewBox=\"0 0 24 24\"><path d=\"M5 17h14l-2-3V9a5 5 0 0 0-10 0v5l-2 3ZM10 21h4M12 2v2\"/></symbol><symbol id=\"i-posts\" viewBox=\"0 0 24 24\"><path d=\"M3 10 12 3 21 10M5 9v11q0 1 1 1h12q1 0 1-1V9M9 17h6\"/><circle cx=\"12\" cy=\"11\" r=\"2.5\" fill=\"currentColor\" stroke=\"none\"/></symbol><symbol id=\"i-photo\" viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"3\" width=\"18\" height=\"18\" rx=\"1\"/><path d=\"m3 16 5-5 6 6 3-3 4 4\"/><circle cx=\"16\" cy=\"8\" r=\"1.5\"/></symbol><symbol id=\"i-play\" viewBox=\"0 0 24 24\"><path d=\"m8 4 13 8-13 8Z\" fill=\"currentColor\" stroke=\"none\"/></symbol><symbol id=\"i-reply\" viewBox=\"0 0 24 24\"><path d=\"M3 4h18v14H8l-5 4Z\"/></symbol><symbol id=\"i-heart\" viewBox=\"0 0 24 24\"><path d=\"M12 21 3.8 13C-2 7 6 0 12 7c6-7 14 0 8.2 6Z\"/></symbol><symbol id=\"i-bolt\" viewBox=\"0 0 24 24\"><path d=\"m14 2-9 12h6l-1 8 9-13h-6Z\" fill=\"currentColor\" stroke=\"none\"/></symbol><symbol id=\"i-article\" viewBox=\"0 0 24 24\"><path d=\"M5 3h14v18H5ZM8 7h8M8 11h8M8 15h8M8 18h5\"/></symbol><symbol id=\"i-podcast\" viewBox=\"0 0 24 24\"><path fill=\"currentColor\" stroke=\"none\" d=\"M12 3a9 9 0 0 0-9 9v7a2 2 0 0 0 2 2h3v-8H5v-1a7 7 0 0 1 14 0v1h-3v8h3a2 2 0 0 0 2-2v-7a9 9 0 0 0-9-9Z\"/></symbol><symbol id=\"i-profile\" viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"8\" r=\"4\"/><path d=\"M4 21v-2a8 8 0 0 1 16 0v2\"/></symbol></defs></svg>";
  const markup = symbols + `<div class="feed-demo">
    <div class="phone-stage" id="feed-stage" role="tabpanel" aria-labelledby="tab-2" tabindex="0">
      ${screens.map((screen, i) => `<figure class="phone" data-page="${i}" data-position="${Math.max(-2, Math.min(2, i - 2))}" aria-hidden="${i !== 2}">
        <picture>${screen.shared ? '' : `<source media="(prefers-color-scheme: dark)" srcset="/assets/screens/${screen.file}-dark.webp">`}
          <img src="/assets/screens/${screen.file}${screen.shared ? '' : '-light'}.webp" alt="Feather ${screen.label.toLowerCase()}${screen.file === 'video-fullscreen' ? ' in the full-screen player' : ' screen'}" width="720" height="1483" draggable="false" loading="lazy">
        </picture><figcaption>${screen.label}</figcaption>
      </figure>`).join('')}
    </div>
    <div class="feed-pill" role="tablist" aria-label="Try the feed switcher">
      ${screens.map((screen, i) => `<button type="button" role="tab" id="tab-${i}" aria-selected="${i === 2}" aria-controls="feed-stage" aria-label="${screen.label}" tabindex="${i === 2 ? 0 : -1}" data-tab="${i}"><svg class="icon" aria-hidden="true" viewBox="0 0 24 24"><use href="#i-${screen.icon}"/></svg></button>`).join('')}
    </div>
    <p class="demo-caption"><span id="feed-label" class="feed-label" aria-live="polite">Home</span><span class="caption-dot">·</span>Swipe or tap to explore</p>
  </div>`;

  customElements.define('feather-feed-switcher', class extends HTMLElement {
    connectedCallback() {
      if (this.dataset.ready) return;
      this.dataset.ready = 'true';
      const prefix = `feather-feed-${++instance}-`;
      this.innerHTML = markup.replace(/id="([^"]+)"/g, (_, id) => `id="${prefix}${id}"`)
        .replace(/href="#([^"]+)"/g, (_, id) => `href="#${prefix}${id}"`)
        .replace(/aria-controls="([^"]+)"/g, (_, id) => `aria-controls="${prefix}${id}"`)
        .replace(/aria-labelledby="([^"]+)"/g, (_, id) => `aria-labelledby="${prefix}${id}"`);
      const $ = (id) => this.querySelector(`[id="${prefix}${id}"]`);
      const clamp = (v, min, max) => Math.max(min, Math.min(max, v));
      const stage = $('feed-stage');
      const pill = this.querySelector('.feed-pill');
      const tabs = [...this.querySelectorAll('[data-tab]')];
      const phones = [...this.querySelectorAll('[data-page]')];
      let page = 2;
      function choosePage(index, focus = false) {
        page = clamp(index, 0, tabs.length - 1);
        phones.forEach((phone, i) => {
          phone.dataset.position = clamp(i - page, -2, 2);
          phone.setAttribute('aria-hidden', String(i !== page));
        });
        tabs.forEach((tab, i) => {
          tab.setAttribute('aria-selected', String(i === page));
          tab.tabIndex = i === page ? 0 : -1;
        });
        stage.setAttribute('aria-labelledby', tabs[page].id);
        $('feed-label').textContent = tabs[page].getAttribute('aria-label');
        const selected = tabs[page];
        pill.scrollTo({
          left: selected.offsetLeft + selected.offsetWidth / 2 - pill.clientWidth / 2,
          behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth'
        });
        if (focus) selected.focus({ preventScroll: true });
      }
      choosePage(page);
      tabs.forEach((tab, i) => {
        tab.addEventListener('click', () => choosePage(i));
        tab.addEventListener('keydown', (e) => {
          const destinations = { ArrowLeft: page - 1, ArrowRight: page + 1, Home: 0, End: tabs.length - 1 };
          if (e.key in destinations) { e.preventDefault(); choosePage(destinations[e.key], true); }
        });
      });
      stage.addEventListener('keydown', (e) => {
        if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
          e.preventDefault(); choosePage(page + (e.key === 'ArrowRight' ? 1 : -1));
    }
  });
  let swipe;
  stage.addEventListener('pointerdown', (e) => {
    if (!e.isPrimary || e.button !== 0) return;
    swipe = { x: e.clientX, y: e.clientY, id: e.pointerId };
    stage.setPointerCapture(e.pointerId);
  });
  stage.addEventListener('pointermove', (e) => {
    if (!swipe || e.pointerId !== swipe.id) return;
    const dx = e.clientX - swipe.x;
    if (Math.abs(dx) > Math.abs(e.clientY - swipe.y)) stage.style.setProperty('--drag', `${clamp(dx, -100, 100)}px`);
  });
  function finishSwipe(e) {
    if (!swipe || e.pointerId !== swipe.id) return;
    const dx = e.clientX - swipe.x;
    if (e.type === 'pointerup' && Math.abs(dx) > 40 && Math.abs(dx) > Math.abs(e.clientY - swipe.y)) choosePage(page + (dx < 0 ? 1 : -1));
    swipe = null; stage.style.removeProperty('--drag');
  }
  stage.addEventListener('pointerup', finishSwipe);
  stage.addEventListener('pointercancel', finishSwipe);
  stage.addEventListener('lostpointercapture', () => { swipe = null; stage.style.removeProperty('--drag'); });

    }
  });
})();
