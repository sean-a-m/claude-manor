/* Operator-only scene client; no simulation state or generated script. */
(() => {
  'use strict';
  const root = document.getElementById('scene-page');
  if (!root) return;
  const byId = id => document.getElementById(id);
  let timer, refreshEpoch = 0, refreshAbort;
  let selected = null, selectionEpoch = 0, selectionAbort;
  let drag = null, suppressClick = false, regionAnchor = null;
  const map = () => byId('scene-map');
  const numbers = text => text.trim().split(/\s+/).map(Number);
  const box = () => numbers(map().getAttribute('viewBox'));
  const setBox = b => { if (b.every(Number.isFinite) && b[2] > 0 && b[3] > 0) map().setAttribute('viewBox', b.join(' ')); };
  const region = id => Array.from(root.querySelectorAll('[data-region]')).find(n => n.dataset.region === id);
  const sameSelection = (a, b) => a && b && a.kind === b.kind && a.id === b.id;

  function markSelection() {
    root.querySelectorAll('[data-kind][data-id]').forEach(n => {
      const match = sameSelection(selected, {kind: n.dataset.kind, id: n.dataset.id});
      n.classList.toggle('selected', !!match);
      n.setAttribute('aria-pressed', match ? 'true' : 'false');
    });
  }
  // Phase 3 can provide data-inspect-base="/partial/inspect/". IDs are always
  // URL path components, never interpolated selectors, HTML or JavaScript.
  async function refreshSelection() {
    const epoch = ++selectionEpoch;
    if (selectionAbort) selectionAbort.abort();
    markSelection();
    const inspector = byId('inspector');
    if (!selected) { inspector.textContent = 'Select a person or place.'; return; }
    const current = {...selected};
    inspector.textContent = `Selected ${current.kind}: ${current.id}`;
    root.dispatchEvent(new CustomEvent('scene:selection', {detail: current}));
    const base = root.dataset.inspectBase;
    if (!base) return;
    selectionAbort = new AbortController();
    try {
      const response = await fetch(base + encodeURIComponent(current.kind) + '/' + encodeURIComponent(current.id), {signal: selectionAbort.signal});
      const text = await response.text();
      if (epoch !== selectionEpoch || !sameSelection(current, selected)) return;
      if (response.ok) inspector.innerHTML = text;
      else inspector.textContent = 'Selection no longer available.';
    } catch (error) {
      if (error.name !== 'AbortError' && epoch === selectionEpoch) inspector.textContent = 'Could not refresh selection.';
    }
  }
  function select(value) {
    selected = value && {kind: value.kind, id: String(value.id)};
    refreshSelection();
  }

  async function update(epoch) {
    refreshAbort = new AbortController();
    try {
      const response = await fetch('/partial/scene-state', {signal: refreshAbort.signal});
      if (!response.ok) throw new Error('Scene refresh failed');
      const state = await response.json();
      if (epoch !== refreshEpoch) return;
      // Capture the latest viewport, including pans made while fetch was pending.
      const previous = box();
      const oldRegion = regionAnchor && region(regionAnchor);
      const oldBounds = oldRegion && numbers(oldRegion.dataset.box);
      byId('scene-container').innerHTML = state.scene;
      byId('scene-toolbar').innerHTML = state.toolbar;
      byId('scene-navigation').innerHTML = state.navigation;
      byId('scene-away').innerHTML = state.away;
      byId('feed').innerHTML = state.feed;
      if (oldBounds) {
        const newRegion = region(regionAnchor);
        if (newRegion) {
          const next = numbers(newRegion.dataset.box);
          previous[0] += next[0] - oldBounds[0];
          previous[1] += next[1] - oldBounds[1];
        }
      }
      setBox(previous);
      byId('scene-client-error').textContent = '';
      refreshSelection();
    } catch (error) {
      if (error.name !== 'AbortError' && epoch === refreshEpoch) byId('scene-client-error').textContent = 'Scene refresh failed; waiting for the next update.';
    }
  }
  function refresh() {
    clearTimeout(timer);
    const epoch = ++refreshEpoch;
    // Invalidate inspector responses immediately, not only when refresh finishes.
    ++selectionEpoch;
    if (selectionAbort) selectionAbort.abort();
    if (refreshAbort) refreshAbort.abort();
    timer = setTimeout(() => update(epoch), 100);
  }
  function zoom(factor, fx = 0.5, fy = 0.5) {
    const [x, y, w, h] = box();
    const nextW = Math.max(0.5, Math.min(10000, w * factor));
    const nextH = h * nextW / w;
    setBox([x + (w - nextW) * fx, y + (h - nextH) * fy, nextW, nextH]);
  }
  function svgPoint(event) {
    const point = map().createSVGPoint();
    point.x = event.clientX; point.y = event.clientY;
    return point.matrixTransform(map().getScreenCTM().inverse());
  }
  root.addEventListener('wheel', event => {
    if (!event.target.closest('#scene-map')) return;
    event.preventDefault();
    const p = svgPoint(event), b = box();
    zoom(event.deltaY > 0 ? 1.15 : 1 / 1.15, (p.x - b[0]) / b[2], (p.y - b[1]) / b[3]);
  }, {passive: false});
  root.addEventListener('pointerdown', event => {
    if (event.button !== 0 || !event.target.closest('#scene-map')) return;
    const p = svgPoint(event);
    drag = {id: event.pointerId, x: event.clientX, y: event.clientY, p, moved: false};
    suppressClick = false;
    // Capture on the stable container; the SVG may be replaced by SSE mid-drag.
    byId('scene-container').setPointerCapture(event.pointerId);
  });
  root.addEventListener('pointermove', event => {
    if (!drag || drag.id !== event.pointerId) return;
    if (Math.hypot(event.clientX - drag.x, event.clientY - drag.y) > 5) drag.moved = true;
    if (!drag.moved) return;
    const p = svgPoint(event), current = box();
    setBox([current[0] + drag.p.x - p.x, current[1] + drag.p.y - p.y, current[2], current[3]]);
  });
  function endDrag(event) {
    if (!drag || drag.id !== event.pointerId) return;
    suppressClick = drag.moved;
    // Pointer capture retargets clicks to the container, so select the release
    // target explicitly for a tap. A drag never selects.
    if (!drag.moved && event.type === 'pointerup') {
      const target = document.elementFromPoint(event.clientX, event.clientY);
      const node = target && target.closest('[data-kind][data-id]');
      if (node && root.contains(node)) select({kind: node.dataset.kind, id: node.dataset.id});
      suppressClick = true;
    }
    drag = null;
  }
  root.addEventListener('pointerup', endDrag);
  root.addEventListener('pointercancel', endDrag);
  root.addEventListener('click', event => {
    if (suppressClick) {
      suppressClick = false;
      if (event.target.closest('#scene-container')) { event.preventDefault(); return; }
    }
    const control = event.target.closest('[data-view], [data-region-target]');
    if (control) {
      if (control.dataset.regionTarget) {
        regionAnchor = control.dataset.regionTarget;
        const [x, y, w, h] = numbers(control.dataset.box);
        setBox([x - 0.3, y - 0.3, w + 0.6, h + 0.6]);
      } else if (control.dataset.view === 'fit') { regionAnchor = null; setBox(numbers(map().dataset.fit)); }
      else zoom(control.dataset.view === 'in' ? 1 / 1.3 : 1.3);
      return;
    }
    const node = event.target.closest('[data-kind][data-id]');
    if (node && root.contains(node)) select({kind: node.dataset.kind, id: node.dataset.id});
  });
  function highlightStair(event, active) {
    const stair = event.target.closest('.connection.stair');
    if (!stair || (event.relatedTarget && stair.contains(event.relatedTarget))) return;
    root.querySelectorAll('.room[data-id]').forEach(node => {
      if (node.dataset.id === stair.dataset.from || node.dataset.id === stair.dataset.to) node.classList.toggle('stair-partner', active);
    });
  }
  root.addEventListener('pointerover', event => highlightStair(event, true));
  root.addEventListener('pointerout', event => highlightStair(event, false));
  root.addEventListener('keydown', event => {
    if (event.key === 'Escape') select(null);
    if ((event.key === 'Enter' || event.key === ' ') && event.target.matches('[data-kind][data-id]')) {
      event.preventDefault(); select({kind: event.target.dataset.kind, id: event.target.dataset.id});
    }
  });
  root.addEventListener('submit', async event => {
    const form = event.target.closest('form[data-command]');
    if (!form) return;
    event.preventDefault();
    try {
      const response = await fetch(form.action, {method: 'POST', body: new URLSearchParams(new FormData(form))});
      if (!response.ok) throw new Error('Command failed');
      refresh();
    } catch (_) { byId('scene-client-error').textContent = 'Command failed.'; }
  });
  const events = new EventSource('/stream');
  events.addEventListener('ev', refresh);
  events.addEventListener('status', refresh);
  window.ManorScene = {select, refresh, refreshSelection, get selection() { return selected && {...selected}; }};
  refreshSelection();
})();
