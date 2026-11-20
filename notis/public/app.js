'use strict';
// Notis — revisa estado en vivo vía /api/status (server.py). Nada simulado.

const PLATS = {
  kick:      { label: 'Kick',      check: true,  profile: u => `https://kick.com/${u}` },
  twitch:    { label: 'Twitch',    check: true,  profile: u => `https://www.twitch.tv/${u}` },
  youtube:   { label: 'YouTube',   check: true,  profile: u => `https://www.youtube.com/@${u}` },
  x:         { label: 'X',         check: false, profile: u => `https://x.com/${u}` },
  instagram: { label: 'Instagram', check: false, profile: u => `https://www.instagram.com/${u}` },
};
const ORDER = Object.keys(PLATS);
const HOSTS = { 'kick.com': 'kick', 'twitch.tv': 'twitch', 'youtube.com': 'youtube', 'x.com': 'x', 'twitter.com': 'x', 'instagram.com': 'instagram' };
const SAFE_HOSTS = ['kick.com', 'www.twitch.tv', 'www.youtube.com', 'x.com', 'www.instagram.com'];
const AVATAR_HOSTS = /(^|\.)(kick\.com|jtvnw\.net|googleusercontent\.com|ggpht\.com)$/;
const USER_RE = /^[A-Za-z0-9_.\-]{1,40}$/;
const KEY = 'vibealert.v1';

const acc = (kick, twitch, youtube, x, instagram) => ({ kick, twitch, youtube, x, instagram });
// Cuentas adivinadas por nombre de usuario: revisarlas en "Editar".
const SEED = {
  streamers: [
    ['alondrissa',    acc('alondrissa', 'alondrissa', '', '', ''), ['Las yeguas'], 'kick'],
    ['Rainelissss',   acc('rainelissss', 'rainelissss', 'rainelissss', '', ''), ['Las yeguas'], 'kick'],
    ['Alex Omar',     acc('alexomar', 'alexomar', 'alexomar', '', ''), ['Las yeguas'], 'kick'],
    ['Caprimint',     acc('caprimint', 'caprimint', 'caprimint', '', ''), ['Las transnochadas'], 'twitch'],
    ['Nezumi',        acc('nezumi', 'nezumivy', '', '', ''), ['Las transnochadas'], 'twitch'],
    ['Cristal Molly', acc('cristalmolly', 'cristalmolly', 'cristalmolly', '', 'cristalmolly'), ['Las transnochadas'], 'twitch'],
    ['Patty Meza',    acc('pattymeza', 'pattymeza', 'pattymeza', 'pattymeza', ''), ['Las transnochadas'], 'twitch'],
  ].map(([name, accounts, groups = [], avatarFrom = '']) => ({ id: uid(), name, accounts, groups, avatarFrom })),
  groups: ['Las yeguas', 'Las transnochadas'],
  interval: 60, sound: true, activity: [],
};

let state = load();
const status = {};          // status[streamerId][platform] = respuesta del servidor
let view = 'all', busy = false, timer = 0, lastCheck = 0, editingId = null, actx = null;

const $ = s => document.querySelector(s);
function uid() { return Math.random().toString(36).slice(2, 10); }
function esc(s) { return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }
function load() {
  try {
    const s = JSON.parse(localStorage.getItem(KEY));
    if (s && Array.isArray(s.streamers)) {
      s.groups = s.groups.map(g => g === 'Las transochadas' ? 'Las transnochadas' : g);
      // datos guardados antes de que existieran los grupos: les aplica los grupos de fábrica por nombre
      if (!s.streamers.some(x => x.groups.length)) s.streamers.forEach(x => { x.groups = SEED.streamers.find(d => d.name === x.name)?.groups || []; });
      // corrección: la cuenta de Twitch de Nezumi es nezumivy (dato del usuario)
      s.streamers.forEach(x => { if (x.name === 'Nezumi' && x.accounts.twitch === 'nezumi') { x.accounts.twitch = 'nezumivy'; x.avatar = null; } });
      s.streamers.forEach(x => { if (x.avatarFrom === undefined) x.avatarFrom = SEED.streamers.find(d => d.name === x.name)?.avatarFrom || ''; });
      return s;
    }
  } catch {}
  return structuredClone(SEED);
}
function save() { try { localStorage.setItem(KEY, JSON.stringify(state)); } catch {} }
function safeUrl(u) {
  try { const x = new URL(u); return x.protocol === 'https:' && SAFE_HOSTS.includes(x.hostname) ? x.href : null; } catch { return null; }
}
function safeAvatar(u) {
  try { const x = new URL(u); return x.protocol === 'https:' && AVATAR_HOSTS.test(x.hostname) ? x.href : null; } catch { return null; }
}
// foto de perfil: primero la plataforma elegida (avatarFrom), luego Kick, Twitch, YouTube; se guarda para no pedirla en cada revisión
async function loadAvatar(s) {
  for (const p of [...new Set([s.avatarFrom, 'kick', 'twitch', 'youtube'].filter(Boolean))]) {
    if (!s.accounts[p]) continue;
    try {
      const r = await (await fetch(`/api/avatar?platform=${p}&user=${encodeURIComponent(s.accounts[p])}`)).json();
      const url = safeAvatar(r.avatar);
      if (url) { s.avatar = url; s.avatarSrc = p; save(); render(); return; }
    } catch {}
  }
  if (s.avatar) { s.avatar = null; save(); render(); } // sin foto: queda la inicial
}
function ago(t) {
  const s = Math.round((Date.now() - t) / 1000);
  return s < 60 ? `hace ${s} s` : s < 3600 ? `hace ${Math.round(s / 60)} min` : `hace ${Math.round(s / 3600)} h`;
}

// "kick.com/x", "https://www.youtube.com/@x", "@x" o "x"  ->  {platform?, user} | {error}
function parseInput(raw) {
  let s = raw.trim();
  if (!s) return { error: 'Escribí un enlace o usuario' };
  if (/^https?:\/\//i.test(s) || /^[\w.-]+\.(com|tv)\//i.test(s)) {
    if (!/^https?:/i.test(s)) s = 'https://' + s;
    let u; try { u = new URL(s); } catch { return { error: 'Ese enlace no se entiende' }; }
    const platform = HOSTS[u.hostname.replace(/^(www|m|mobile)\./, '')];
    if (!platform) return { error: 'Solo Kick, Twitch, YouTube, X e Instagram' };
    let user = u.pathname.split('/').filter(Boolean)[0] || '';
    if (platform === 'youtube') {
      if (!user.startsWith('@')) return { error: 'Para YouTube usá el enlace con @ (youtube.com/@canal)' };
    }
    user = user.replace(/^@/, '');
    return USER_RE.test(user) ? { platform, user } : { error: 'No encontré el usuario en el enlace' };
  }
  const user = s.replace(/^@/, '');
  return USER_RE.test(user) ? { user } : { error: 'Usuario inválido (solo letras, números, _ . -)' };
}

function liveAccounts(s) {
  const st = status[s.id] || {};
  return ORDER.filter(p => st[p]?.live).map(p => ({ p, ...st[p], url: safeUrl(st[p].url) || PLATS[p].profile(s.accounts[p]) }));
}
function visible() {
  let list = state.streamers;
  if (view === 'live') list = list.filter(s => liveAccounts(s).length);
  else if (view.startsWith('group:')) list = list.filter(s => s.groups.includes(view.slice(6)));
  return [...list].sort((a, b) => liveAccounts(b).length - liveAccounts(a).length || a.name.localeCompare(b.name));
}

// ---------- render ----------
function render() {
  const liveN = state.streamers.filter(s => liveAccounts(s).length).length;
  document.title = liveN ? `(${liveN}) Notis` : 'Notis';
  const item = (id, label, n, live) =>
    `<button data-view="${esc(id)}" ${view === id ? 'aria-current="page"' : ''}>${esc(label)}<span class="count ${live ? 'live' : ''}">${n ?? ''}</span></button>`;
  $('#nav').innerHTML =
    `<div class="nav-label">Vista</div>` +
    item('all', 'Todos', state.streamers.length) +
    item('live', 'En vivo ahora', liveN, liveN > 0) +
    item('activity', 'Actividad', state.activity.length || '') +
    `<div class="nav-label">Grupos</div>` +
    state.groups.map(g => item('group:' + g, g, state.streamers.filter(s => s.groups.includes(g)).length)).join('');

  const isAct = view === 'activity';
  $('#activity').hidden = !isAct;
  $('#grid').hidden = isAct;
  $('#viewTitle').textContent = isAct ? 'Actividad' : view === 'all' ? 'Todos' : view === 'live' ? 'En vivo ahora' : view.slice(6);
  $('#viewSub').textContent = isAct ? 'Historial de directos detectados.'
    : view.startsWith('group:') && !visible().length ? 'Grupo vacío: abrí "Editar" en un streamer y marcá este grupo.'
    : `Revisa Kick, Twitch y YouTube cada ${state.interval >= 60 ? state.interval / 60 + ' min' : state.interval + ' s'}.`;

  if (isAct) {
    $('#actList').innerHTML = state.activity.length ? state.activity.map(a =>
      `<li><time>${esc(new Date(a.t).toLocaleString('es', { dateStyle: 'short', timeStyle: 'short' }))}</time>
       <span><b>${esc(a.name)}</b> prendió en ${esc(PLATS[a.p]?.label)}${a.title ? ': ' + esc(a.title) : ''}</span>
       ${safeUrl(a.url) ? `<a href="${esc(safeUrl(a.url))}" target="_blank" rel="noopener">Abrir</a>` : ''}</li>`).join('')
      : '<li>Todavía no se ha detectado ningún directo nuevo.</li>';
    return;
  }

  renderOnAir();
  const list = visible();
  setHTML($('#grid'), list.length ? list.map((s, i) => card(s, i)).join('')
    : `<div class="empty">${view === 'live' ? 'Nadie en vivo ahorita.' : 'No hay streamers aquí todavía.'}</div>`);
}

// solo reescribe si cambió: así las fotos y animaciones no parpadean en cada revisión
function setHTML(el, html) { if (el._html !== html) { el.innerHTML = html; el._html = html; } }
function avatar(s, cls = 'avatar') {
  const src = safeAvatar(s.avatar);
  return `<div class="${cls}" aria-hidden="true"><span>${esc(s.name.slice(0, 1).toUpperCase())}</span>${src ? `<img src="${esc(src)}" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.remove()">` : ''}</div>`;
}

// letrero "Al aire": lo más llamativo de la app; muestra en grande a quien esté en vivo
function renderOnAir() {
  const live = state.streamers.map(s => [s, liveAccounts(s)]).filter(([, l]) => l.length);
  const sign = $('#onair');
  sign.classList.toggle('lit', live.length > 0);
  document.body.style.setProperty('--glow', live.length ? `var(--${live[0][1][0].p})` : 'var(--tally)');
  setHTML($('#onairList'), live.length ? live.map(([s, l]) => `
    <article class="onair-item p-${l[0].p}">
      ${avatar(s, 'avatar xl')}
      <div class="onair-info">
        <h3>${esc(s.name)}</h3>
        <p class="onair-plat">En vivo en ${l.map(x => PLATS[x.p].label).join(' y ')}${l[0].viewers != null ? `<span class="viewers">${esc(l[0].viewers)} viendo</span>` : l[0].uptime ? `<span class="viewers">${esc(l[0].uptime.split(',').slice(0, 2).join(','))}</span>` : ''}</p>
        ${l[0].title ? `<p class="onair-title">${esc(l[0].title)}</p>` : ''}
        <div class="onair-go">${l.map(x => `<a class="btn go p-${x.p}" href="${esc(x.url)}" target="_blank" rel="noopener">Ver en ${PLATS[x.p].label}</a>`).join('')}</div>
      </div>
    </article>`).join('')
    : `<p class="onair-empty">Nadie está transmitiendo. Cuando alguien prenda, suena el timbre y te llega la notificación.</p>`);
}

function card(s, i) {
  const st = status[s.id] || {};
  const live = liveAccounts(s);
  const checked = Math.max(0, ...ORDER.map(p => st[p]?.checked || 0));
  const meta = live.length ? `En vivo en ${live.map(l => PLATS[l.p].label).join(' y ')}`
    : checked ? `Apagado, revisado ${ago(checked)}` : 'Sin revisar todavía';
  const chips = ORDER.filter(p => s.accounts[p]).map(p => {
    const r = st[p], P = PLATS[p];
    const cls = !P.check ? 'nodetect' : r?.live ? 'live' : r?.error ? 'err' : '';
    const tip = !P.check ? 'Solo enlace: sin API de pago no se pueden detectar publicaciones'
      : r?.live ? 'En vivo' : r?.error ? r.error : r ? 'Apagado' : 'Sin revisar';
    const href = (r?.live && safeUrl(r.url)) || P.profile(s.accounts[p]);
    return `<a class="acct p-${p} ${cls}" href="${esc(href)}" target="_blank" rel="noopener" title="${esc(tip)}"><i></i><b>${P.label}</b><span>${esc(s.accounts[p])}</span>${r?.error ? '<em>sin respuesta</em>' : ''}</a>`;
  }).join('');
  return `<article class="card ${live.length ? 'is-live p-' + live[0].p : ''}" style="--i:${i}">
    <span class="tally" title="${live.length ? 'En vivo' : 'Apagado'}"></span>
    <div class="card-head">
      ${avatar(s)}
      <div class="card-id"><h3 class="card-name">${esc(s.name)}</h3><p class="card-meta">${esc(meta)}</p></div>
    </div>
    ${live.length ? `<a class="btn go p-${live[0].p}" href="${esc(live[0].url)}" target="_blank" rel="noopener">Ver en ${PLATS[live[0].p].label}</a>` : ''}
    <div class="accts">${chips || '<span class="card-meta">Sin cuentas</span>'}</div>
    <div class="card-foot">
      ${s.groups.map(g => `<span class="tag">${esc(g)}</span>`).join('')}
      <button class="btn ghost sm" data-edit="${s.id}" aria-label="Editar ${esc(s.name)}">Editar</button>
    </div>
  </article>`;
}

function setEngine(kind, text) { $('#engineDot').className = 'dot ' + kind; $('#engineText').textContent = text; }

// ---------- revisión ----------
async function checkOne(s, p) {
  let r;
  try {
    const res = await fetch(`/api/status?platform=${p}&user=${encodeURIComponent(s.accounts[p])}`);
    r = await res.json();
    if (!res.ok) r = { live: false, error: r.error || 'Error' };
  } catch { r = { live: false, error: 'Servidor apagado (corré server.py)' }; }
  const prev = status[s.id]?.[p];
  (status[s.id] ||= {})[p] = { ...r, checked: Date.now() };
  // primera revisión = línea base; solo avisa cuando pasa de apagado a en vivo
  if (r.live && prev && !prev.live) notifyLive(s, p, status[s.id][p]);
}

async function checkAll() {
  if (busy) return;
  busy = true; setEngine('busy', 'Revisando…'); $('#checkBtn').disabled = true;
  const jobs = state.streamers.flatMap(s => ORDER.filter(p => PLATS[p].check && s.accounts[p]).map(p => [s, p]));
  let k = 0;
  await Promise.all(Array.from({ length: 6 }, async () => { while (k < jobs.length) { const [s, p] = jobs[k++]; await checkOne(s, p); render(); } }));
  busy = false; lastCheck = Date.now(); $('#checkBtn').disabled = false;
  const errs = Object.values(status).flatMap(o => Object.values(o)).filter(r => r.error?.startsWith('Servidor')).length;
  setEngine(errs ? '' : 'on', errs ? 'Servidor apagado' : `Revisado a las ${new Date(lastCheck).toLocaleTimeString('es', { timeStyle: 'short' })}`);
  render();
}

function schedule() { clearTimeout(timer); timer = setTimeout(async () => { await checkAll(); schedule(); }, state.interval * 1000); }

// ---------- alarma ----------
function unlockAudio() {
  try { actx ||= new (window.AudioContext || window.webkitAudioContext)(); if (actx.state === 'suspended') actx.resume(); } catch {}
}
function chime() {
  if (!state.sound) return;
  unlockAudio(); if (!actx) return;
  const t0 = actx.currentTime + 0.05;
  [0, .16, .32, .9, 1.06, 1.22].forEach((dt, i) => {
    const o = actx.createOscillator(), g = actx.createGain();
    o.type = 'triangle'; o.frequency.value = [784, 988, 1319][i % 3];
    g.gain.setValueAtTime(0.0001, t0 + dt);
    g.gain.exponentialRampToValueAtTime(0.3, t0 + dt + 0.02);
    g.gain.exponentialRampToValueAtTime(0.0001, t0 + dt + 0.35);
    o.connect(g).connect(actx.destination); o.start(t0 + dt); o.stop(t0 + dt + 0.4);
  });
}

function notifyLive(s, p, r, test = false) {
  const url = safeUrl(r.url) || PLATS[p].profile(s.accounts[p] || '');
  const title = `${s.name} prendió en ${PLATS[p].label}`;
  if (!test) {
    state.activity.unshift({ t: Date.now(), name: s.name, p, title: r.title || '', url });
    state.activity.length = Math.min(state.activity.length, 200);
    save();
  }
  chime();
  const bell = $('#brandBell'); bell.classList.remove('ring'); void bell.offsetWidth; bell.classList.add('ring');
  if ('Notification' in window && Notification.permission === 'granted') {
    const n = new Notification(title, { body: r.title || 'Tocá para abrir el stream', tag: s.id + p, requireInteraction: true });
    n.onclick = () => { window.open(url, '_blank', 'noopener'); n.close(); };
  }
  const t = document.createElement('div');
  t.className = `toast p-${p}`; t.setAttribute('role', 'alert');
  t.innerHTML = `${avatar(s)}<div class="toast-body"><strong>${esc(title)}${test ? ' (prueba)' : ''}</strong><p>${esc(r.title || '')}</p>
    <div class="row"><a class="btn go p-${p} sm" href="${esc(url)}" target="_blank" rel="noopener">Ver en ${PLATS[p].label}</a><button class="btn ghost sm">Cerrar</button></div></div>`;
  t.querySelector('button').onclick = () => t.remove();
  t.querySelector('a').addEventListener('click', () => t.remove());
  $('#toasts').prepend(t);
  setTimeout(() => t.remove(), 60000);
}

// ---------- notificaciones / controles ----------
function syncNotifBtn() {
  const b = $('#notifBtn');
  if (!('Notification' in window)) { b.textContent = 'Notificaciones no soportadas'; b.disabled = true; return; }
  const p = Notification.permission;
  b.textContent = p === 'granted' ? 'Notificaciones: sí' : p === 'denied' ? 'Notificaciones bloqueadas' : 'Activar notificaciones';
  b.title = p === 'denied' ? 'Habilitalas en el candado de la barra de direcciones' : '';
}
function syncSoundBtn() { $('#soundBtn').textContent = `Sonido: ${state.sound ? 'sí' : 'no'}`; $('#soundBtn').setAttribute('aria-pressed', String(state.sound)); }

$('#notifBtn').onclick = async () => { unlockAudio(); if ('Notification' in window) await Notification.requestPermission(); syncNotifBtn(); };
$('#soundBtn').onclick = () => { state.sound = !state.sound; save(); syncSoundBtn(); if (state.sound) unlockAudio(); };
$('#testBtn').onclick = () => {
  const s = state.streamers.find(x => x.accounts.kick || x.accounts.twitch) || { id: 'test', name: 'Prueba', accounts: { kick: 'kick' } };
  const p = s.accounts.kick ? 'kick' : 'twitch';
  notifyLive(s, p, { title: 'Así se ve y suena el aviso' }, true);
};
$('#checkBtn').onclick = () => checkAll().then(schedule);
state.streamers.filter(s => !s.avatar || s.avatar.includes('user-default') || (s.avatarFrom && s.avatarSrc !== s.avatarFrom)).forEach(loadAvatar);
$('#interval').value = String(state.interval);
$('#interval').onchange = e => { state.interval = Number(e.target.value); save(); render(); schedule(); };
document.addEventListener('pointerdown', unlockAudio, { passive: true });

$('#nav').onclick = e => { const b = e.target.closest('[data-view]'); if (b) { view = b.dataset.view; render(); } };

$('#groupForm').onsubmit = e => {
  e.preventDefault();
  const g = $('#groupName').value.trim();
  if (g && !state.groups.some(x => x.toLowerCase() === g.toLowerCase())) { state.groups.push(g); save(); }
  $('#groupName').value = ''; render();
};

// ---------- agregar ----------
$('#addForm').onsubmit = e => {
  e.preventDefault();
  const msg = $('#addMsg'); msg.className = 'hint';
  const r = parseInput($('#addInput').value);
  if (r.error) { msg.textContent = r.error; msg.classList.add('err'); return; }
  const plats = r.platform ? [r.platform] : [...$('#addPlats').querySelectorAll('input:checked')].map(i => i.value);
  if (!plats.length) { msg.textContent = 'Marcá al menos una plataforma'; msg.classList.add('err'); return; }
  const name = $('#addName').value.trim() || r.user;
  let s = state.streamers.find(x => x.name.toLowerCase() === name.toLowerCase());
  const isNew = !s;
  if (!s) { s = { id: uid(), name, accounts: acc('', '', '', '', ''), groups: view.startsWith('group:') ? [view.slice(6)] : [] }; state.streamers.push(s); }
  plats.forEach(p => { s.accounts[p] = r.user; });
  save();
  if (!s.avatar) loadAvatar(s);
  msg.textContent = `${isNew ? 'Agregado' : 'Actualizado'}: ${name} (${plats.map(p => PLATS[p].label).join(', ')}). Revisando…`;
  $('#addInput').value = ''; $('#addName').value = '';
  render();
  Promise.all(plats.filter(p => PLATS[p].check).map(p => checkOne(s, p))).then(render);
};

// ---------- editar ----------
$('#grid').addEventListener('click', e => {
  const b = e.target.closest('[data-edit]'); if (!b) return;
  const s = state.streamers.find(x => x.id === b.dataset.edit); if (!s) return;
  editingId = s.id;
  $('#eName').value = s.name;
  $('#eAccounts').innerHTML = ORDER.map(p => `<div class="field"><label for="e-${p}">${PLATS[p].label}${PLATS[p].check ? '' : ' (solo enlace)'}</label>
    <input id="e-${p}" data-p="${p}" value="${esc(s.accounts[p])}" placeholder="usuario o enlace" autocomplete="off"></div>`).join('');
  $('#eAvatar').value = s.avatarFrom || '';
  $('#eGroups').innerHTML = state.groups.map(g => `<label><input type="checkbox" value="${esc(g)}" ${s.groups.includes(g) ? 'checked' : ''}> ${esc(g)}</label>`).join('')
    || '<span class="hint">No hay grupos.</span>';
  $('#editDlg').showModal();
});
$('#editDlg').addEventListener('close', () => {
  const s = state.streamers.find(x => x.id === editingId), how = $('#editDlg').returnValue;
  if (!s) return;
  if (how === 'delete' && confirm(`¿Eliminar a ${s.name}?`)) {
    state.streamers = state.streamers.filter(x => x !== s); delete status[s.id];
  } else if (how === 'save') {
    s.name = $('#eName').value.trim() || s.name;
    const bad = [];
    $('#eAccounts').querySelectorAll('input').forEach(inp => {
      const p = inp.dataset.p, v = inp.value.trim();
      if (!v) { s.accounts[p] = ''; delete status[s.id]?.[p]; return; }
      const r = parseInput(v);
      if (r.error || (r.platform && r.platform !== p)) { bad.push(PLATS[p].label); return; }
      if (s.accounts[p] !== r.user) { s.accounts[p] = r.user; delete status[s.id]?.[p]; }
    });
    const from = $('#eAvatar').value;
    if (from !== (s.avatarFrom || '') || !s.avatar) { s.avatarFrom = from; s.avatar = null; loadAvatar(s); }
    s.groups = [...$('#eGroups').querySelectorAll('input:checked')].map(i => i.value);
    if (bad.length) alert(`No se guardó: ${bad.join(', ')} (enlace o usuario inválido)`);
    Promise.all(ORDER.filter(p => PLATS[p].check && s.accounts[p] && !status[s.id]?.[p]).map(p => checkOne(s, p))).then(render);
  }
  save(); render();
});

// luz que sigue al cursor
$('#grid').addEventListener('pointermove', e => {
  const c = e.target.closest('.card'); if (!c) return;
  const r = c.getBoundingClientRect();
  c.style.setProperty('--mx', `${e.clientX - r.left}px`); c.style.setProperty('--my', `${e.clientY - r.top}px`);
});

// autocomprobación del parser (se ve en la consola si algo falla)
console.assert(parseInput('kick.com/rainelissss').platform === 'kick', 'parse kick');
console.assert(parseInput('https://www.twitch.tv/pattymeza?x=1').user === 'pattymeza', 'parse twitch');
console.assert(parseInput('youtube.com/@LofiGirl/live').user === 'LofiGirl', 'parse yt');
console.assert(parseInput('youtube.com/watch?v=abc').error, 'yt sin @');
console.assert(parseInput('@alondrissa').user === 'alondrissa' && !parseInput('@alondrissa').platform, 'parse user');
console.assert(parseInput('x.com/pattymeza/status/123').user === 'pattymeza', 'parse x');
console.assert(parseInput('evil.com/x').error, 'host raro');

syncNotifBtn(); syncSoundBtn(); render();
setTimeout(() => $('#grid').classList.add('settled'), 800); // la entrada suave solo al abrir, no en cada revisión
checkAll().then(schedule);
state.streamers.filter(s => !s.avatar || s.avatar.includes('user-default') || (s.avatarFrom && s.avatarSrc !== s.avatarFrom)).forEach(loadAvatar);
