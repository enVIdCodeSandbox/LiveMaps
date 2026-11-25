(() => {
'use strict';
const $ = id => document.getElementById(id);
const HDR = { 'X-LiveMaps': '1', 'Content-Type': 'application/json' };
const LABEL = { BLUEMAP: 'BlueMap', SQUAREMAP: 'squaremap' };

let ST = null;          // /api/state
let source = null;      // 'BLUEMAP' | 'SQUAREMAP'
let previewTimer = 0, previewCtl = null, checkCtl = null, lastCheck = null, objUrl = null;

// BlueMap capture state
let bmLoaded = false;
let bmCaptured = false;
let capturedDataUrl = null;
let capturedW = 0, capturedH = 0;
let capturedHash = '';
let capturedTarget = 'world';

const num = (id, d = 0) => { const v = parseFloat($(id).value); return Number.isFinite(v) ? v : d; };
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
const zoomFromSlider = v => 0.125 * Math.pow(2, (v / 1000) * 11);
const zoomToSlider = z => Math.round(clamp(Math.log2(z / 0.125) / 11, 0, 1) * 1000);
const fmt = v => (v >= 100 ? v.toFixed(0) : v >= 10 ? v.toFixed(1) : v.toFixed(2)).replace(/\.?0+$/, '');
const formatDuration = sec => {
  const s = Math.max(1, Math.round(sec || 600));
  if (s % 3600 === 0) return `${s / 3600}h`;
  if (s % 60 === 0) return `${s / 60}m`;
  return `${s}s`;
};

async function api(path, opts) {
  const r = await fetch(path, Object.assign({ credentials: 'same-origin', cache: 'no-store' }, opts));
  if (r.status === 401) { showLogin(); throw new Error('login'); }
  return r;
}

function showLogin() {
  $('app').hidden = true; $('login').hidden = false; $('logout').hidden = true;
  if (new URLSearchParams(location.search).get('login') === 'invalid') $('loginTitle').textContent = 'That link is invalid or has expired';
}

// ------------------------------------------------------------------------------------ setup

async function init() {
  let r, j;
  try {
    r = await api('/api/state');
    j = await r.json();
  } catch (e) { return; }
  if (!j.player) { $('login').hidden = false; $('loginTitle').textContent = j.error || 'Unavailable'; return; }
  try {
    ST = j;
    $('login').hidden = true; $('app').hidden = false; $('logout').hidden = false;
    $('who').textContent = `${j.player.name} · ${j.free ? 'creative (free)' : 'survival'}`;
    if (j.bluemapUrl) {
      const a = $('bmOpen');
      a.href = j.bluemapUrl;
      a.hidden = false;
      const syncLink = () => {
        let h = '';
        try { h = $('bmFrame').contentWindow.location.hash || ''; } catch (e) {}
        a.href = ST.bluemapUrl.split('#')[0] + h;
      };
      a.addEventListener('mouseenter', syncLink);
      a.addEventListener('focus', syncLink);
    }

    $('width').max = j.limits.maxWidth; $('width').min = j.limits.minWidth;
    $('height').max = j.limits.maxHeight; $('height').min = j.limits.minHeight;
    $('width').value = Math.max(j.limits.minWidth, Math.min(3, j.limits.maxWidth));
    $('height').value = Math.max(j.limits.minHeight, Math.min(2, j.limits.maxHeight));
    $('sizeLimits').textContent = `${j.limits.minWidth}×${j.limits.minHeight} to ${j.limits.maxWidth}×${j.limits.maxHeight}`;

    const d = j.defaults;
    $('shading').value = Math.round(d.shading * 100);
    $('dither').value = d.dither; $('supersample').value = String(d.supersample);
    $('x').value = Math.round(j.player.x); $('z').value = Math.round(j.player.z);
    $('zoom').value = zoomToSlider(1);

    const defOpt = $('refreshPreset').querySelector('option[value=""]');
    if (defOpt) defOpt.textContent = `Server default (${formatDuration(j.updateSeconds)})`;
    $('refreshSec').value = String(j.updateSeconds || 600);
    setRefreshIntervalUi(null);

    populateSnapshots(j.mySnapshots || []);

    const tabs = $('sourceTabs'); tabs.textContent = '';
    for (const s of j.sources) {
      const b = document.createElement('button');
      b.textContent = LABEL[s.type] || s.type; b.dataset.source = s.type; b.disabled = !s.ready;
      b.title = s.ready ? '' : 'Not installed or not ready on this server';
      b.addEventListener('click', () => setSource(s.type));
      tabs.appendChild(b);
    }
    const first = j.sources.find(s => s.ready);
    if (!first) {
      $('stageMsg').textContent = 'Neither BlueMap nor squaremap is ready on this server yet.';
      $('stageMsg').hidden = false;
      $('create').disabled = true; $('sourceHint').textContent = 'No map source is available. Ask an admin to check /livemap sources.';
      return;
    }
    bind();
    setSource(first.type, true);
  } catch (e) {
    console.error('LiveMaps init error:', e);
    $('stageMsg').textContent = 'Web UI error: ' + (e && e.message ? e.message : String(e)) + ' (try hard-refreshing the page)';
    $('stageMsg').hidden = false;
    $('create').disabled = true;
  }
}

function populateSnapshots(list) {
  const sel = $('updateId');
  const prev = sel.value;
  sel.textContent = '';
  const def = document.createElement('option');
  def.value = '0'; def.textContent = 'Create a new map';
  sel.appendChild(def);
  for (const m of list) {
    const o = document.createElement('option');
    o.value = String(m.id);
    o.textContent = `Update #${m.id} (${m.width}×${m.height} · ${m.target})`;
    sel.appendChild(o);
  }
  if ([...sel.options].some(o => o.value === prev)) sel.value = prev;
  $('bmUpdateRow').hidden = list.length === 0;
}

function defaultBmTarget() {
  const s = ST && ST.sources.find(x => x.type === 'BLUEMAP');
  if (!s || !s.targets.length) return 'world';
  const mine = s.targets.find(t => t.world === ST.player.world || t.id === ST.player.world);
  return (mine || s.targets[0]).id;
}

function setSource(type, initial) {
  source = type;
  for (const b of $('sourceTabs').children) b.classList.toggle('on', b.dataset.source === type);
  const s = ST.sources.find(x => x.type === type);
  const sel = $('target'); sel.textContent = '';
  for (const t of s.targets) {
    const o = document.createElement('option');
    o.value = t.id; o.textContent = t.name + (t.world && t.world !== t.name ? ` (${t.world})` : '');
    sel.appendChild(o);
  }
  const mine = s.targets.find(t => t.world === ST.player.world || t.id === ST.player.world);
  if (mine) sel.value = mine.id;

  const isBm = type === 'BLUEMAP';
  $('bmControls').hidden = !isBm;
  $('bmUpdateRow').hidden = !isBm || !(ST.mySnapshots && ST.mySnapshots.length);
  $('sqControls').hidden = isBm;
  $('sqTargetRow').hidden = isBm;
  $('sqLookRow').hidden = isBm;
  $('sqSmoothRow').hidden = isBm;

  if (isBm) {
    $('sourceHint').textContent = '';
    $('howto').textContent = 'You get a kit item. Put an empty item frame on every spot the map will cover (wall, floor or ceiling), then right-click the top-left frame with the kit. BlueMap captures are snapshots; you can update them here anytime.';
    ensureBmFrameLoaded();
  } else {
    $('sourceHint').textContent = 'squaremap only provides a top-down view and refreshes automatically.';
    bmCaptured = false;
  }
  updateRefreshHint();
  if (!initial) $('createMsg').textContent = '';
  changed(true);
}

function ensureBmFrameLoaded(hash) {
  const base = (ST && ST.bluemapEmbedUrl) || '/bluemap/';
  if (!bmLoaded) {
    bmLoaded = true;
    $('bmFrame').src = hash ? base + hash : base;
  } else if (hash) {
    jumpBmHash(hash);
  }
}

function jumpBmHash(hash) {
  const f = $('bmFrame');
  const h = hash.startsWith('#') ? hash : '#' + hash;
  try {
    if (f.contentWindow && f.contentWindow.location) {
      f.contentWindow.location.hash = h;
      return;
    }
  } catch (e) {}
  f.src = ((ST && ST.bluemapEmbedUrl) || '/bluemap/') + h;
}

function bind() {
  $('updateId').addEventListener('change', () => {
    const id = parseInt($('updateId').value, 10) || 0;
    const snap = (ST.mySnapshots || []).find(m => m.id === id);
    if (snap) {
      $('width').value = snap.width;
      $('height').value = snap.height;
      $('width').disabled = true;
      $('height').disabled = true;
      $('dither').value = snap.dither;
      setRefreshIntervalUi(snap.refreshIntervalSeconds || null);
      if (snap.bluemapHash) ensureBmFrameLoaded(snap.bluemapHash);
    } else {
      $('width').disabled = false;
      $('height').disabled = false;
      setRefreshIntervalUi(null);
    }
    updateRefreshHint();
    bmCaptured = false;
    capturedDataUrl = null;
    changed(true);
  });

  for (const id of ['width', 'height']) {
    $(id).addEventListener('input', () => {
      if (source === 'BLUEMAP' && bmCaptured) {
        bmCaptured = false;
        capturedDataUrl = null;
      }
      changed();
    });
  }

  for (const id of ['target', 'x', 'z', 'yaw', 'zoom', 'shading', 'supersample']) {
    $(id).addEventListener('input', changed);
  }
  for (const id of ['dither', 'palette']) {
    $(id).addEventListener('input', () => {
      if (source === 'BLUEMAP') {
        syncUi();
        if (bmCaptured) runSnapshotPreview();
      } else {
        changed();
      }
    });
  }

  $('refreshPreset').addEventListener('change', () => {
    $('refreshCustomRow').hidden = $('refreshPreset').value !== 'custom';
    updateRefreshHint();
  });
  $('refreshSec').addEventListener('input', () => {
    updateRefreshHint();
  });

  $('confirm').addEventListener('change', updateCreate);
  $('useMe').addEventListener('click', () => { $('x').value = Math.round(ST.player.x); $('z').value = Math.round(ST.player.z); changed(); });

  $('bmJumpMe').addEventListener('click', () => {
    const mapId = currentBmTarget() || defaultBmTarget();
    const x = Math.round(ST.player.x);
    const y = Math.round(ST.player.y);
    const z = Math.round(ST.player.z);
    const rot = (ST.player.lookYaw * Math.PI / 180).toFixed(3);
    const tilt = clamp((ST.player.lookTilt * Math.PI / 180), 0.1, 1.55).toFixed(3);
    if (bmCaptured) { bmCaptured = false; capturedDataUrl = null; syncUi(); }
    jumpBmHash(`#${mapId}:${x}:${y}:${z}:80:${rot}:${tilt}:0:0:perspective`);
  });

  $('bmImport').addEventListener('click', () => {
    const raw = $('bmUrl').value.trim();
    if (!raw) return;
    const i = raw.indexOf('#');
    const h = i >= 0 ? raw.slice(i) : '#' + raw;
    if (h.split(':').length < 4) {
      $('bmUrl').setCustomValidity('Paste a BlueMap URL ending in #map:x:y:z:…');
      $('bmUrl').reportValidity();
      $('bmUrl').setCustomValidity('');
      return;
    }
    if (bmCaptured) { bmCaptured = false; capturedDataUrl = null; syncUi(); }
    jumpBmHash(h);
  });

  $('togglePanel').addEventListener('click', () => {
    const app = $('app'); app.classList.toggle('nopanel');
    $('togglePanel').textContent = app.classList.contains('nopanel') ? 'Show controls' : 'Hide controls';
    fit();
  });

  $('showGrid').addEventListener('change', () => {
    const on = $('showGrid').checked;
    $('grid').hidden = !on;
    $('cropGrid').hidden = !on;
    fit();
  });

  $('captureBtn').addEventListener('click', captureBlueMap);
  $('retakeBtn').addEventListener('click', () => {
    bmCaptured = false;
    $('stageMsg').hidden = true;
    syncUi();
    updateCreate();
  });

  new ResizeObserver(fit).observe($('stage'));
  window.addEventListener('resize', fit);
  $('preview').addEventListener('load', fit);
  $('create').addEventListener('click', create);
  $('logout').addEventListener('click', async () => { try { await api('/api/logout', { method: 'POST', headers: HDR }); } catch (e) {} location.href = '/'; });

  $('stage').addEventListener('wheel', ev => {
    if (source !== 'SQUAREMAP') return;
    ev.preventDefault();
    $('zoom').value = clamp(num('zoom') + (ev.deltaY > 0 ? 25 : -25), 0, 1000);
    changed();
  }, { passive: false });
}

function getRefreshIntervalSeconds() {
  const preset = $('refreshPreset').value;
  if (!preset) return null;
  if (preset === 'custom') {
    const v = Math.round(num('refreshSec', ST ? ST.updateSeconds : 600));
    return clamp(v, 5, 86400);
  }
  const v = parseInt(preset, 10);
  return Number.isFinite(v) && v > 0 ? clamp(v, 5, 86400) : null;
}

function setRefreshIntervalUi(sec) {
  const sel = $('refreshPreset');
  if (!sec || sec <= 0) {
    sel.value = '';
    $('refreshCustomRow').hidden = true;
    return;
  }
  const s = String(sec);
  if ([...sel.options].some(o => o.value === s)) {
    sel.value = s;
    $('refreshCustomRow').hidden = true;
  } else {
    sel.value = 'custom';
    $('refreshSec').value = String(sec);
    $('refreshCustomRow').hidden = false;
  }
}

function updateRefreshHint() {
  if (!source) return;
  const eff = getRefreshIntervalSeconds() || (ST ? ST.updateSeconds : 600);
  if (source === 'BLUEMAP') {
    $('refreshHint').textContent = 'BlueMap WebGL captures stay static until you re-capture them here.';
  } else {
    $('refreshHint').textContent = `Re-renders every ${formatDuration(eff)} while a player is nearby or holding a tile.`;
    $('howto').textContent = `You get a kit item. Put an empty item frame on every spot the map will cover (wall, floor or ceiling), then right-click the top-left frame with the kit. The map refreshes every ${formatDuration(eff)} while someone is nearby.`;
  }
}

function currentBmTarget() {
  try {
    const h = $('bmFrame').contentWindow.location.hash || '';
    if (h.startsWith('#')) {
      const id = h.slice(1).split(':')[0];
      const s = ST && ST.sources.find(x => x.type === 'BLUEMAP');
      if (id && s && s.targets.some(t => t.id === id)) return id;
    }
  } catch (e) {}
  return defaultBmTarget();
}

// ------------------------------------------------------------------------------------ spec & UI

function spec() {
  const w = Math.max(1, Math.round(num('width', 1)));
  const h = Math.max(1, Math.round(num('height', 1)));
  if (source === 'BLUEMAP') {
    const updateId = parseInt($('updateId').value, 10) || 0;
    return {
      source: 'BLUEMAP',
      target: capturedTarget || defaultBmTarget(),
      mode: 'FREE',
      projection: 'PERSPECTIVE',
      width: w,
      height: h,
      dither: $('dither').value,
      snapshot: true,
      updateId,
      refreshIntervalSeconds: getRefreshIntervalSeconds(),
    };
  }
  return {
    source: 'SQUAREMAP',
    target: $('target').value,
    mode: 'FLAT',
    x: num('x'),
    z: num('z'),
    yaw: num('yaw'),
    tilt: 0,
    zoom: zoomFromSlider(num('zoom')),
    projection: 'ORTHOGRAPHIC',
    width: w,
    height: h,
    shading: num('shading') / 100,
    dither: $('dither').value,
    supersample: $('supersample').value,
    refreshIntervalSeconds: getRefreshIntervalSeconds(),
  };
}

function syncUi() {
  const w = Math.max(1, Math.round(num('width', 1)));
  const h = Math.max(1, Math.round(num('height', 1)));
  $('total').textContent = w * h;

  if (source === 'BLUEMAP') {
    const updateId = parseInt($('updateId').value, 10) || 0;
    $('create').textContent = updateId > 0 ? `Update live map #${updateId}` : 'Create live map';
    $('bmWrap').hidden = false;
    $('bmWrap').classList.toggle('bg', bmCaptured);
    $('frame').hidden = !bmCaptured;
    $('captureBtn').hidden = bmCaptured;
    $('retakeBtn').hidden = !bmCaptured;
    $('cropBadge').textContent = `${w}×${h} maps (${w * 128}×${h * 128} px)`;
    if (!bmCaptured) {
      $('previewInfo').textContent = `Target size: ${w * 128}×${h * 128} pixels (${w}×${h} maps). Frame your shot in BlueMap and click Take Screenshot.`;
    }
  } else {
    $('create').textContent = 'Create live map';
    $('bmWrap').hidden = true;
    $('frame').hidden = false;
    $('captureBtn').hidden = true;
    $('retakeBtn').hidden = true;
    $('yawOut').textContent = `${num('yaw')}°`;
    const z = zoomFromSlider(num('zoom'));
    $('zoomOut').textContent = `${fmt(z)} blocks/pixel · covers ${Math.round(w * 128 * z)}×${Math.round(h * 128 * z)}`;
    $('shadingOut').textContent = `${num('shading')}%`;
  }
  fit();
}

function fit() {
  if (!source) return;
  const st = $('stage');
  const aw = st.clientWidth, ah = st.clientHeight;
  if (!aw || !ah) return;
  const gw = Math.max(1, Math.round(num('width', 1)));
  const gh = Math.max(1, Math.round(num('height', 1)));
  const pw = gw * 128, ph = gh * 128;

  // Crop overlay inside BlueMap
  if (source === 'BLUEMAP') {
    const csc = Math.min((aw * 0.86) / pw, (ah * 0.80) / ph);
    const cb = $('cropBox');
    cb.style.width = Math.max(24, Math.floor(pw * csc)) + 'px';
    cb.style.height = Math.max(24, Math.floor(ph * csc)) + 'px';
    $('cropGrid').style.backgroundSize = `calc(100% / ${gw}) calc(100% / ${gh})`;
  }

  // Static preview frame
  const fr = $('frame'), img = $('preview');
  const sc = Math.min(aw / pw, ah / ph);
  fr.style.width = Math.floor(pw * sc) + 'px';
  fr.style.height = Math.floor(ph * sc) + 'px';
  img.classList.toggle('sm', sc < 1);
  $('grid').style.backgroundSize = `calc(100% / ${gw}) calc(100% / ${gh})`;
  img.style.visibility = (img.naturalWidth === pw && img.naturalHeight === ph && img.classList.contains('on')) ? 'visible' : 'hidden';
  fit.scale = sc;
}

function changed(immediate) {
  syncUi();
  lastCheck = null;
  updateCreate();
  clearTimeout(previewTimer);
  if (source === 'BLUEMAP') {
    previewTimer = setTimeout(() => {
      runCheck();
      if (bmCaptured) runSnapshotPreview();
    }, immediate ? 10 : 180);
  } else {
    previewTimer = setTimeout(refresh, immediate ? 10 : 350);
  }
}

function refresh() { runCheck(); runPreview(); }

// ------------------------------------------------------------------------------------ BlueMap WebGL capture

async function captureBlueMap() {
  const frameEl = $('bmFrame');
  let doc, win;
  try {
    win = frameEl.contentWindow;
    doc = frameEl.contentDocument || (win && win.document);
  } catch (e) {
    $('stageMsg').textContent = 'Cannot access BlueMap iframe (same-origin error).';
    $('stageMsg').hidden = false;
    return;
  }
  const webglCanvas = doc && doc.querySelector('canvas');
  if (!webglCanvas || !webglCanvas.width || !webglCanvas.height) {
    $('stageMsg').textContent = 'BlueMap is still loading — wait a moment and try again.';
    $('stageMsg').hidden = false;
    return;
  }

  const gw = Math.max(1, Math.round(num('width', 1)));
  const gh = Math.max(1, Math.round(num('height', 1)));

  const frameRect = frameEl.getBoundingClientRect();
  const cropRect = $('cropBox').getBoundingClientRect();
  const canvasRect = webglCanvas.getBoundingClientRect();
  if (!canvasRect.width || !canvasRect.height) return;

  const scaleX = webglCanvas.width / canvasRect.width;
  const scaleY = webglCanvas.height / canvasRect.height;
  const cropInIframeX = cropRect.left - frameRect.left;
  const cropInIframeY = cropRect.top - frameRect.top;

  const sx = clamp((cropInIframeX - canvasRect.left) * scaleX, 0, webglCanvas.width - 1);
  const sy = clamp((cropInIframeY - canvasRect.top) * scaleY, 0, webglCanvas.height - 1);
  const sw = clamp(cropRect.width * scaleX, 1, webglCanvas.width - sx);
  const sh = clamp(cropRect.height * scaleY, 1, webglCanvas.height - sy);

  // Capture crisp native WebGL pixels (up to 2560px) so the server performs linear-light Lanczos resampling & Oklab quantization
  const maxSide = 2560;
  let capW = Math.max(1, Math.round(sw));
  let capH = Math.max(1, Math.round(sh));
  if (capW > maxSide || capH > maxSide) {
    const k = maxSide / Math.max(capW, capH);
    capW = Math.max(1, Math.round(capW * k));
    capH = Math.max(1, Math.round(capH * k));
  }
  const out = document.createElement('canvas');
  out.width = capW; out.height = capH;
  const octx = out.getContext('2d');
  octx.imageSmoothingEnabled = (capW !== Math.round(sw) || capH !== Math.round(sh));
  octx.imageSmoothingQuality = 'high';
  octx.drawImage(webglCanvas, sx, sy, sw, sh, 0, 0, capW, capH);

  capturedDataUrl = out.toDataURL('image/png');
  capturedW = gw;
  capturedH = gh;
  capturedHash = (win && win.location && win.location.hash) ? win.location.hash : '';
  capturedTarget = currentBmTarget();
  bmCaptured = true;
  $('stageMsg').hidden = true;
  syncUi();
  runCheck();
  runSnapshotPreview();
}

async function runSnapshotPreview() {
  if (!bmCaptured || !capturedDataUrl) return;
  if (previewCtl) previewCtl.abort();
  const ctl = previewCtl = new AbortController();
  $('stage').classList.add('busy'); $('stageMsg').textContent = 'Resampling & applying map palette…'; $('stageMsg').hidden = false;
  const t0 = performance.now();
  try {
    const r = await api('/api/preview-image', {
      method: 'POST',
      headers: HDR,
      signal: ctl.signal,
      body: JSON.stringify({
        width: capturedW,
        height: capturedH,
        dither: $('dither').value,
        palette: $('palette').checked,
        image: capturedDataUrl,
      }),
    });
    if ((r.headers.get('content-type') || '').startsWith('image/')) {
      const blob = await r.blob();
      if (ctl !== previewCtl) return;
      if (objUrl) URL.revokeObjectURL(objUrl);
      objUrl = URL.createObjectURL(blob);
      const img = $('preview'); img.src = objUrl; img.classList.add('on');
      $('stageMsg').hidden = true;
      const px = `${capturedW * 128}×${capturedH * 128} pixels (${capturedW}×${capturedH} maps)`;
      const big = fit.scale >= 1.5 ? ` · shown at ${fit.scale.toFixed(1)}× so you can see how coarse it really is in game` : '';
      $('previewInfo').textContent = `BlueMap snapshot · ${px}, processed in ${Math.round(performance.now() - t0)} ms${big}.`;
      fit();
    } else {
      let msg = 'Preview failed'; try { msg = (await r.json()).error || msg; } catch (e) {}
      if (ctl !== previewCtl) return;
      $('stageMsg').textContent = msg; $('stageMsg').hidden = false;
    }
  } catch (e) {
    if (e.name === 'AbortError' || e.message === 'login') return;
    $('stageMsg').textContent = 'Could not reach the server'; $('stageMsg').hidden = false;
  } finally {
    if (ctl === previewCtl) $('stage').classList.remove('busy');
    updateCreate();
  }
}

// ------------------------------------------------------------------------------------ squaremap preview + checks

async function runPreview() {
  if (!source || source === 'BLUEMAP') return;
  if (previewCtl) previewCtl.abort();
  const ctl = previewCtl = new AbortController();
  const q = new URLSearchParams(); const s = spec();
  for (const k in s) { if (s[k] != null) q.set(k, String(s[k])); }
  q.set('palette', $('palette').checked ? '1' : '0');
  $('stage').classList.add('busy'); $('stageMsg').textContent = 'Rendering…'; $('stageMsg').hidden = false;
  const t0 = performance.now();
  try {
    const r = await api('/api/preview?' + q, { signal: ctl.signal });
    if ((r.headers.get('content-type') || '').startsWith('image/')) {
      const blob = await r.blob();
      if (ctl !== previewCtl) return;
      if (objUrl) URL.revokeObjectURL(objUrl);
      objUrl = URL.createObjectURL(blob);
      const img = $('preview'); img.src = objUrl; img.classList.add('on');
      $('stageMsg').hidden = true;
      const px = `${s.width * 128}×${s.height * 128} pixels (${s.width}×${s.height} maps)`;
      const big = fit.scale >= 1.5 ? ` · shown at ${fit.scale.toFixed(1)}× so you can see how coarse it really is in game` : '';
      $('previewInfo').textContent = `${px}, rendered in ${Math.round(performance.now() - t0)} ms${big}.`;
      fit();
    } else {
      let msg = 'Preview failed'; try { msg = (await r.json()).error || msg; } catch (e) {}
      if (ctl !== previewCtl) return;
      $('stageMsg').textContent = msg; $('stageMsg').hidden = false;
    }
  } catch (e) {
    if (e.name === 'AbortError' || e.message === 'login') return;
    $('stageMsg').textContent = 'Could not reach the server'; $('stageMsg').hidden = false;
  } finally { if (ctl === previewCtl) $('stage').classList.remove('busy'); }
}

async function runCheck() {
  if (!source) return;
  if (checkCtl) checkCtl.abort();
  const ctl = checkCtl = new AbortController();
  try {
    const r = await api('/api/check', { method: 'POST', headers: HDR, body: JSON.stringify(spec()), signal: ctl.signal });
    const j = await r.json();
    if (ctl !== checkCtl) return;
    lastCheck = j.errors ? j : { ok: false, errors: [j.error || 'Check failed'], warnings: [] };
    renderCheck();
  } catch (e) { /* aborted or logged out */ }
}

function renderCheck() {
  const c = lastCheck, line = $('costLine'); line.textContent = '';
  const list = $('issues'); list.textContent = '';
  if (!c) { updateCreate(); return; }
  const updateId = source === 'BLUEMAP' ? (parseInt($('updateId').value, 10) || 0) : 0;
  if (updateId > 0) {
    const s = document.createElement('span'); s.className = 'free'; s.textContent = `Free (updating existing map #${updateId})`; line.appendChild(s);
  } else if (c.free) {
    const s = document.createElement('span'); s.className = 'free'; s.textContent = 'Free (creative mode)'; line.appendChild(s);
  } else if (ST.cost.enabled) {
    line.append(`Cost: ${c.cost} × ${ST.cost.material}, you have `);
    const s = document.createElement('span'); s.className = c.have >= c.cost ? '' : 'short'; s.textContent = c.have; line.appendChild(s);
  }
  for (const e of c.errors || []) { const li = document.createElement('li'); li.className = 'e'; li.textContent = e; list.appendChild(li); }
  for (const w of c.warnings || []) { const li = document.createElement('li'); li.className = 'w'; li.textContent = w; list.appendChild(li); }
  $('confirmRow').hidden = !c.needsConfirm;
  if (!c.needsConfirm) $('confirm').checked = false;
  updateCreate();
}

function updateCreate() {
  const c = lastCheck;
  const needShot = source === 'BLUEMAP' && !bmCaptured;
  $('create').disabled = !source || needShot || !c || !c.ok || (c.needsConfirm && !$('confirm').checked);
}

async function create() {
  $('create').disabled = true;
  const m = $('createMsg'); m.className = ''; m.textContent = 'Saving…';
  try {
    let r;
    if (source === 'BLUEMAP') {
      if (!bmCaptured || !capturedDataUrl) { m.className = 'err'; m.textContent = 'Take a screenshot first.'; updateCreate(); return; }
      const updateId = parseInt($('updateId').value, 10) || 0;
      r = await api('/api/create-snapshot', {
        method: 'POST',
        headers: HDR,
        body: JSON.stringify({
          target: capturedTarget || defaultBmTarget(),
          width: capturedW,
          height: capturedH,
          dither: $('dither').value,
          confirmLarge: $('confirm').checked,
          updateId,
          bluemapHash: capturedHash,
          refreshIntervalSeconds: getRefreshIntervalSeconds(),
          image: capturedDataUrl,
        }),
      });
    } else {
      const body = Object.assign(spec(), { confirmLarge: $('confirm').checked });
      r = await api('/api/create', { method: 'POST', headers: HDR, body: JSON.stringify(body) });
    }
    const j = await r.json();
    if (j.ok) {
      m.className = 'ok'; m.textContent = j.message;
      // Refresh state so new snapshots appear in the Update dropdown and map count updates
      try {
        const sr = await api('/api/state');
        const sj = await sr.json();
        if (sj && sj.player) { ST = sj; populateSnapshots(sj.mySnapshots || []); }
      } catch (e) {}
    } else {
      m.className = 'err'; m.textContent = j.error || 'Could not create the map';
    }
  } catch (e) { if (e.message !== 'login') { m.className = 'err'; m.textContent = 'Request failed'; } }
  runCheck();
}

init();
})();
