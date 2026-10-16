/* Lightweight, local previews. No account, posting, uploads, analytics, or API keys. */
(() => {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const motion = matchMedia('(prefers-reduced-motion: reduce)');
  const icon = (name) => `<svg class="icon" aria-hidden="true" viewBox="0 0 24 24"><use href="#i-${name}"/></svg>`;
  const clamp = (v, min, max) => Math.max(min, Math.min(max, v));
  const stage = $('feed-stage');
  const tabs = [...document.querySelectorAll('[data-tab]')];
  const phones = [...document.querySelectorAll('[data-page]')];
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

  // The video is intentionally user-started, and pauses out of view.
  const video = $('demo-video');
  video.controls = false;
  let feedbackTimer;
  function feedback(text) {
    $('gesture-feedback').textContent = text;
    $('gesture-feedback').classList.add('visible');
    clearTimeout(feedbackTimer);
    feedbackTimer = setTimeout(() => $('gesture-feedback').classList.remove('visible'), 900);
  }
  function setBrightness(value) {
    const amount = clamp(value, 25, 100);
    $('brightness').value = amount;
    $('player').style.setProperty('--brightness', amount / 100);
    feedback(`Brightness ${Math.round(amount)}%`);
  }
  function setVolume(value) {
    const amount = clamp(value, 0, 100);
    $('volume').value = amount;
    video.volume = amount / 100; video.muted = amount === 0;
    $('mute').setAttribute('aria-label', amount === 0 ? 'Unmute video' : 'Mute video');
    feedback(`Volume ${Math.round(amount)}% · silent sample`);
  }
  $('brightness').addEventListener('input', (e) => setBrightness(Number(e.target.value)));
  $('volume').addEventListener('input', (e) => setVolume(Number(e.target.value)));
  $('mute').addEventListener('click', () => setVolume(video.muted ? 60 : 0));
  $('play').addEventListener('click', () => {
    if (video.paused) video.play().catch(() => feedback('Tap again to play'));
    else video.pause();
  });
  function playbackState() {
    $('play').innerHTML = icon(video.paused ? 'play' : 'pause');
    $('play').setAttribute('aria-label', video.paused ? 'Play video' : 'Pause video');
  }
  video.addEventListener('play', playbackState);
  video.addEventListener('pause', playbackState);
  video.addEventListener('error', () => feedback('Video unavailable. Please reload.'));
  const time = (seconds) => `${Math.floor(seconds / 60)}:${String(Math.floor(seconds % 60)).padStart(2, '0')}`;
  video.addEventListener('timeupdate', () => {
    if (!Number.isFinite(video.duration)) return;
    $('seek').value = video.currentTime / video.duration * 100;
    $('video-time').textContent = `${time(video.currentTime)} / ${time(video.duration)}`;
  });
  $('seek').addEventListener('input', (e) => {
    if (Number.isFinite(video.duration)) video.currentTime = Number(e.target.value) / 100 * video.duration;
    else { video.load(); feedback('Press play to load the clip'); }
  });
  const speeds = [1, 1.25, 1.5, 2, 3];
  $('video-speed').addEventListener('click', () => {
    video.playbackRate = speeds[(speeds.indexOf(video.playbackRate) + 1) % speeds.length];
    $('video-speed').textContent = `${video.playbackRate}×`;
    $('video-speed').setAttribute('aria-label', `Playback speed: ${video.playbackRate}x`);
  });
  $('fullscreen').addEventListener('click', async () => {
    try {
      if (document.fullscreenElement) await document.exitFullscreen();
      else if ($('player').requestFullscreen) await $('player').requestFullscreen();
      else if (video.webkitEnterFullscreen) video.webkitEnterFullscreen();
      else feedback('Full screen is unavailable in this browser');
    } catch { feedback('Full screen is unavailable'); }
  });
  document.addEventListener('fullscreenchange', () => $('fullscreen').setAttribute('aria-label', document.fullscreenElement ? 'Exit full screen' : 'Enter full screen'));
  const surface = $('gesture-surface');
  let gesture;
  surface.addEventListener('pointerdown', (e) => {
    if (!e.isPrimary || e.button !== 0) return;
    const bounds = surface.getBoundingClientRect();
    gesture = { id: e.pointerId, x: e.clientX, y: e.clientY, bounds, start: video.currentTime, brightness: Number($('brightness').value), volume: Number($('volume').value), direction: null };
    surface.setPointerCapture(e.pointerId);
  });
  surface.addEventListener('pointermove', (e) => {
    if (!gesture || gesture.id !== e.pointerId) return;
    const dx = e.clientX - gesture.x, dy = gesture.y - e.clientY;
    if (!gesture.direction && Math.max(Math.abs(dx), Math.abs(dy)) > 8) gesture.direction = Math.abs(dx) > Math.abs(dy) ? 'seek' : 'vertical';
    if (gesture.direction === 'seek') {
      if (Number.isFinite(video.duration)) {
        video.currentTime = clamp(gesture.start + dx / gesture.bounds.width * video.duration, 0, video.duration);
        feedback(`Seek ${time(video.currentTime)}`);
      } else feedback('Press play to load the clip');
    } else if (gesture.direction === 'vertical') {
      const delta = dy / gesture.bounds.height * 100;
      if (gesture.x - gesture.bounds.left < gesture.bounds.width / 2) setBrightness(gesture.brightness + delta);
      else setVolume(gesture.volume + delta);
    }
  });
  surface.addEventListener('pointerup', (e) => {
    if (gesture && gesture.id === e.pointerId && !gesture.direction) $('play').click();
    gesture = null;
  });
  surface.addEventListener('pointercancel', () => { gesture = null; });
  surface.addEventListener('lostpointercapture', () => { gesture = null; });
  new IntersectionObserver(([entry]) => { if (!entry.isIntersecting) video.pause(); }, {threshold: .1}).observe($('player'));

  // Six reusable GIF slots. Media is loaded only for visible slots; no live search API.
  const safeMedia = (url) => typeof url === 'string' && url.startsWith('https://gifs.nostr.build/') && !url.includes('#');
  let catalog = [], pickerVisible = false, autoplayDenied = false;
  const slots = Array.from({length: 6}, () => {
    const button = document.createElement('button'); button.type = 'button'; button.className = 'gif-tile';
    const clip = document.createElement('video'); clip.muted = true; clip.loop = true; clip.playsInline = true; clip.preload = 'none';
    const still = document.createElement('img'); still.alt = ''; still.hidden = true;
    button.append(clip, still); $('gif-grid').append(button);
    const slot = {button, clip, still, item: null};
    button.addEventListener('click', () => { if (slot.item) attachGif(slot.item); });
    clip.addEventListener('error', () => showStill(slot));
    return slot;
  });
  function release(clip) { clip.pause(); clip.removeAttribute('src'); clip.load(); }
  function showStill(slot) {
    release(slot.clip); slot.clip.hidden = true;
    if (slot.item && safeMedia(slot.item.still)) { slot.still.src = slot.item.still; slot.still.hidden = false; }
  }
  function playSlot(slot) {
    if (!slot.item || slot.button.hidden) return;
    if (motion.matches || autoplayDenied) { showStill(slot); return; }
    slot.clip.hidden = false; slot.still.hidden = true; slot.still.removeAttribute('src');
    const url = slot.item.mp4 + '#t=0.001';
    if (slot.clip.getAttribute('src') !== url) slot.clip.src = url;
    slot.clip.play().catch((error) => {
      if (error.name === 'AbortError') return;
      if (error.name === 'NotAllowedError') autoplayDenied = true;
      if (pickerVisible && !document.hidden) showStill(slot);
    });
  }
  function syncPicker() {
    const active = pickerVisible && !$('gif-picker').hidden && !document.hidden;
    slots.forEach((slot) => { if (active) playSlot(slot); else slot.clip.pause(); });
  }
  function filterGifs() {
    const query = $('gif-search').value.trim().toLocaleLowerCase();
    const items = catalog.filter((item) => [item.title, ...item.tags].join(' ').toLocaleLowerCase().includes(query));
    slots.forEach((slot, index) => {
      release(slot.clip); slot.still.removeAttribute('src'); slot.still.hidden = true;
      slot.item = items[index] || null; slot.button.hidden = !slot.item;
      if (slot.item) slot.button.setAttribute('aria-label', `Attach ${slot.item.title}`);
    });
    $('gif-empty').hidden = items.length > 0;
    syncPicker();
  }
  let catalogRequested = false;
  const pickerObserver = new IntersectionObserver(async ([entry]) => {
    pickerVisible = entry.isIntersecting;
    if (pickerVisible && !catalogRequested) {
      catalogRequested = true;
      try {
        const response = await fetch('/gifs.json'); if (!response.ok) throw new Error();
        const data = await response.json();
        catalog = data.filter((item) => safeMedia(item.mp4) && safeMedia(item.still)).slice(0, 6);
        filterGifs();
      } catch { $('gif-empty').textContent = 'GIF previews are unavailable. Please reload.'; $('gif-empty').hidden = false; slots.forEach((slot) => { slot.button.hidden = true; }); }
    }
    syncPicker();
  }, {threshold: .1});
  pickerObserver.observe($('gif-grid'));
  $('gif-search').addEventListener('input', filterGifs);
  $('gif-toggle').addEventListener('click', () => {
    $('gif-picker').hidden = !$('gif-picker').hidden;
    $('gif-toggle').setAttribute('aria-expanded', String(!$('gif-picker').hidden)); syncPicker();
  });
  let localUrl = null, attachmentSource = null, attachmentVisible = false;
  const attachedVideo = $('attached-video'), attachedStill = $('attached-still');
  function clearAttachment() {
    release(attachedVideo); attachedVideo.hidden = true;
    attachedStill.hidden = true; attachedStill.removeAttribute('src');
    if (localUrl) URL.revokeObjectURL(localUrl);
    localUrl = null; attachmentSource = null; $('attachment').hidden = true; $('local-media').value = '';
  }
  function syncAttachment() {
    if (!attachmentSource || !attachmentVisible || document.hidden) { attachedVideo.pause(); return; }
    if (attachmentSource.kind === 'gif') {
      if (motion.matches || autoplayDenied) {
        attachedVideo.hidden = true; attachedStill.src = attachmentSource.still; attachedStill.hidden = false;
      } else {
        attachedVideo.hidden = false; attachedStill.hidden = true;
        attachedVideo.play().catch(() => {
          if (!attachmentSource || attachmentSource.kind !== 'gif') return;
          attachedVideo.hidden = true; attachedStill.src = attachmentSource.still; attachedStill.hidden = false;
        });
      }
    }
  }
  function attachGif(item) {
    clearAttachment(); attachmentSource = {...item, kind: 'gif'};
    attachedVideo.src = item.mp4 + '#t=0.001'; attachedVideo.controls = false;
    attachedStill.alt = item.title; $('attachment').hidden = false;
    $('composer-status').textContent = 'GIF attached to your demo draft. Nothing is published.';
    $('gif-picker').hidden = true; $('gif-toggle').setAttribute('aria-expanded', 'false'); syncPicker(); syncAttachment();
    $('draft').focus({preventScroll: true});
  }
  new IntersectionObserver(([entry]) => { attachmentVisible = entry.isIntersecting; syncAttachment(); }, {threshold: .1}).observe($('attachment'));
  $('remove-attachment').addEventListener('click', () => { clearAttachment(); $('composer-status').textContent = 'Attachment removed. Your draft stays in this browser.'; });
  $('local-media').addEventListener('change', (e) => {
    const file = e.target.files[0]; if (!file) return;
    if (file.size > 50 * 1024 * 1024 || !/^(image\/(jpeg|png|gif|webp|avif)|video\/(mp4|webm|quicktime))$/.test(file.type)) {
      $('composer-status').textContent = 'For this demo, choose an image or video under 50 MB.'; e.target.value = ''; return;
    }
    clearAttachment(); localUrl = URL.createObjectURL(file); attachmentSource = {kind: 'local'};
    if (file.type.startsWith('video/')) { attachedVideo.src = localUrl; attachedVideo.hidden = false; attachedVideo.controls = true; }
    else { attachedStill.src = localUrl; attachedStill.alt = 'Your selected image'; attachedStill.hidden = false; }
    $('attachment').hidden = false;
    $('composer-status').textContent = 'Local preview only. Your file has not been uploaded.';
  });
  document.querySelector('.media-pick').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); $('local-media').click(); }
  });
  let previewing = false;
  const notePreview = document.createElement('div'); notePreview.className = 'note-preview'; notePreview.hidden = true;
  $('draft').before(notePreview);
  $('demo-send').addEventListener('click', () => {
    if (!previewing && !$('draft').value.trim() && !attachmentSource) { $('draft').focus(); return; }
    previewing = !previewing; notePreview.textContent = $('draft').value.trimEnd(); notePreview.hidden = !previewing; $('draft').hidden = previewing;
    $('demo-send').innerHTML = icon(previewing ? 'undo' : 'send');
    $('demo-send').setAttribute('aria-label', previewing ? 'Continue editing' : 'Preview your note');
    $('composer-status').textContent = previewing ? 'Your note preview. Nothing was published. Tap undo to keep writing.' : 'Try a draft. Nothing is uploaded or published.';
    if (!previewing) $('draft').focus({preventScroll: true});
  });
  $('draft').addEventListener('keydown', (e) => { if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); $('demo-send').click(); } });
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) { video.pause(); attachedVideo.pause(); }
    else syncAttachment();
    syncPicker();
  });
  motion.addEventListener('change', () => { syncPicker(); syncAttachment(); });
  window.addEventListener('pagehide', () => { video.pause(); attachedVideo.pause(); slots.forEach((slot) => slot.clip.pause()); });
})();
