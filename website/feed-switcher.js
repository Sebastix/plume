/* Shared static screenshot demo for the homepage and blog. */
(() => {
  'use strict';
  let instance = 0;
  const markup = "<svg class=\"symbols\" xmlns=\"http://www.w3.org/2000/svg\" aria-hidden=\"true\"><defs><symbol id=\"i-search\" viewBox=\"0 0 24 24\"><circle cx=\"10.5\" cy=\"10.5\" r=\"6.5\"/><path d=\"m16 16 5 5\"/></symbol><symbol id=\"i-bell\" viewBox=\"0 0 24 24\"><path d=\"M5 17h14l-2-3V9a5 5 0 0 0-10 0v5l-2 3ZM10 21h4M12 2v2\"/></symbol><symbol id=\"i-posts\" viewBox=\"0 0 24 24\"><path d=\"M4 6h16M4 12h16M4 18h16\"/></symbol><symbol id=\"i-photo\" viewBox=\"0 0 24 24\"><rect x=\"3\" y=\"3\" width=\"18\" height=\"18\" rx=\"1\"/><path d=\"m3 16 5-5 6 6 3-3 4 4\"/><circle cx=\"16\" cy=\"8\" r=\"1.5\"/></symbol><symbol id=\"i-play\" viewBox=\"0 0 24 24\"><path d=\"m8 4 13 8-13 8Z\" fill=\"currentColor\" stroke=\"none\"/></symbol><symbol id=\"i-reply\" viewBox=\"0 0 24 24\"><path d=\"M3 4h18v14H8l-5 4Z\"/></symbol><symbol id=\"i-heart\" viewBox=\"0 0 24 24\"><path d=\"M12 21 3.8 13C-2 7 6 0 12 7c6-7 14 0 8.2 6Z\"/></symbol><symbol id=\"i-bolt\" viewBox=\"0 0 24 24\"><path d=\"m14 2-9 12h6l-1 8 9-13h-6Z\" fill=\"currentColor\" stroke=\"none\"/></symbol></defs></svg><div class=\"feed-demo\"><div class=\"phone-stage\" id=\"feed-stage\" role=\"tabpanel\" aria-labelledby=\"tab-2\" tabindex=\"0\"><figure class=\"phone\" data-page=\"0\" data-position=\"-2\" aria-hidden=\"true\"><img src=\"/assets/screens/search.webp\" alt=\"Feather search screen\" width=\"720\" height=\"1458\" draggable=\"false\" loading=\"lazy\"><figcaption>Search</figcaption></figure><figure class=\"phone\" data-page=\"1\" data-position=\"-1\" aria-hidden=\"true\"><img src=\"/assets/screens/notifications.webp\" alt=\"Feather notifications screen\" width=\"720\" height=\"1458\" draggable=\"false\" loading=\"lazy\"><figcaption>Notifications</figcaption></figure><figure class=\"phone\" data-page=\"2\" data-position=\"0\" aria-hidden=\"false\"><img src=\"/assets/screens/feed.webp\" alt=\"Feather posts screen\" width=\"720\" height=\"1458\" draggable=\"false\" loading=\"lazy\"><figcaption>Posts</figcaption></figure><figure class=\"phone\" data-page=\"3\" data-position=\"1\" aria-hidden=\"true\"><img src=\"/assets/screens/photos.webp\" alt=\"Feather photos screen\" width=\"720\" height=\"1564\" draggable=\"false\" loading=\"lazy\"><figcaption>Photos</figcaption></figure><figure class=\"phone video-phone\" data-page=\"4\" data-position=\"2\" aria-hidden=\"true\"><div class=\"video-feed\"><div class=\"mock-author\"><img src=\"/assets/feather.svg\" alt=\"\" width=\"32\" height=\"32\"><b>Feather</b><span>now</span></div><p>A little closer.</p><img src=\"/assets/demo/flower.jpg\" alt=\"A bee on a pink flower\" width=\"960\" height=\"540\" loading=\"lazy\"><div class=\"mock-actions\"><svg class=\"icon\" aria-hidden=\"true\" viewBox=\"0 0 24 24\"><use href=\"#i-reply\"/></svg><svg class=\"icon\" aria-hidden=\"true\" viewBox=\"0 0 24 24\"><use href=\"#i-heart\"/></svg><svg class=\"icon\" aria-hidden=\"true\" viewBox=\"0 0 24 24\"><use href=\"#i-bolt\"/></svg></div><div class=\"video-feed-next\"><span>Videos, full width.</span><svg class=\"icon\" aria-hidden=\"true\" viewBox=\"0 0 24 24\"><use href=\"#i-play\"/></svg></div></div><figcaption>Videos</figcaption></figure></div><div class=\"feed-pill\" role=\"tablist\" aria-label=\"Try the feed switcher\"><button type=\"button\" role=\"tab\" id=\"tab-0\" aria-selected=\"false\" aria-controls=\"feed-stage\" aria-label=\"Search\" tabindex=\"-1\" data-tab=\"0\"><svg class=\"icon\" aria-hidden=\"true\" viewBox=\"0 0 24 24\"><use href=\"#i-search\"/></svg></button><button type=\"button\" role=\"tab\" id=\"tab-1\" aria-selected=\"false\" aria-controls=\"feed-stage\" aria-label=\"Notifications\" tabindex=\"-1\" data-tab=\"1\"><svg class=\"icon\" aria-hidden=\"true\" viewBox=\"0 0 24 24\"><use href=\"#i-bell\"/></svg></button><button type=\"button\" role=\"tab\" id=\"tab-2\" aria-selected=\"true\" aria-controls=\"feed-stage\" aria-label=\"Posts\" tabindex=\"0\" data-tab=\"2\"><svg class=\"icon\" aria-hidden=\"true\" viewBox=\"0 0 24 24\"><use href=\"#i-posts\"/></svg></button><button type=\"button\" role=\"tab\" id=\"tab-3\" aria-selected=\"false\" aria-controls=\"feed-stage\" aria-label=\"Photos\" tabindex=\"-1\" data-tab=\"3\"><svg class=\"icon\" aria-hidden=\"true\" viewBox=\"0 0 24 24\"><use href=\"#i-photo\"/></svg></button><button type=\"button\" role=\"tab\" id=\"tab-4\" aria-selected=\"false\" aria-controls=\"feed-stage\" aria-label=\"Videos\" tabindex=\"-1\" data-tab=\"4\"><svg class=\"icon\" aria-hidden=\"true\" viewBox=\"0 0 24 24\"><use href=\"#i-play\"/></svg></button></div><p class=\"demo-caption\"><span id=\"feed-label\" class=\"feed-label\" aria-live=\"polite\">Posts</span><span class=\"caption-dot\">\u00b7</span>Swipe or tap to explore</p></div>";
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
      const tabs = [...this.querySelectorAll('[data-tab]')];
      const phones = [...this.querySelectorAll('[data-page]')];
      let page = 2;
      function choosePage(index, focus = false) {
        page = clamp(index, 0, tabs.length - 1);
        phones.forEach((phone, i) => {
          phone.dataset.position = i - page;
          phone.setAttribute('aria-hidden', String(i !== page));
        });
        tabs.forEach((tab, i) => {
          tab.setAttribute('aria-selected', String(i === page));
          tab.tabIndex = i === page ? 0 : -1;
        });
        stage.setAttribute('aria-labelledby', tabs[page].id);
        $('feed-label').textContent = tabs[page].getAttribute('aria-label');
        if (focus) tabs[page].focus();
      }
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
