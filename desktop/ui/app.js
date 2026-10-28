'use strict';

const glyph = {
  home: '<path d="m3 10 9-7 9 7v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 19z"/><path d="M9 20v-6h6v6"/>',
  game: '<path d="M5 9 7 6h10l2 3 1.5 6.5c.4 1.8-1.6 2.9-2.9 1.6l-2.3-2.2H8.7l-2.3 2.2c-1.3 1.3-3.3.2-2.9-1.6z"/><path d="M7.5 9.5v4m-2-2h4m6.5-.9h.01m2 1.8h.01"/>',
  library: '<rect x="4" y="4" width="16" height="16" rx="2"/><path d="M9 4v16m4-11h3m-3 4h3"/>',
  bios: '<rect x="5" y="5" width="14" height="14" rx="2"/><path d="M9 9h6v6H9zM9 2v3m6-3v3M9 19v3m6-3v3M2 9h3m-3 6h3m14-6h3m-3 6h3"/>',
  download: '<path d="M12 3v12m-4-4 4 4 4-4M4 18v3h16v-3"/>',
  settings: '<circle cx="12" cy="12" r="3"/><path d="M19 12a7 7 0 0 0-.08-1l2-1.5-2-3.4-2.35.9a7 7 0 0 0-1.7-1L14.5 3h-4l-.37 3a7 7 0 0 0-1.7 1l-2.35-.9-2 3.4 2 1.5a7 7 0 0 0 0 2l-2 1.5 2 3.4 2.35-.9a7 7 0 0 0 1.7 1l.37 3h4l.37-3a7 7 0 0 0 1.7-1l2.35.9 2-3.4-2-1.5a7 7 0 0 0 .08-1z"/>',
  account: '<circle cx="12" cy="8" r="3.5"/><path d="M5 20v-1a7 7 0 0 1 14 0v1"/>',
  console: '<rect x="4" y="4" width="16" height="16" rx="3"/><path d="M4 10h16M8 15h4M16 15h.01"/>',
  play: '<path d="m8 5 11 7-11 7z"/>',
  arrow: '<path d="M19 12H5m6-6-6 6 6 6"/>',
  spark: '<path d="m12 3 1.5 5.5L19 10l-5.5 1.5L12 17l-1.5-5.5L5 10l5.5-1.5L12 3ZM19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8L19 15Z"/>'
};
const icon = (name, size = 19) => `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${glyph[name] || ''}</svg>`;
const $ = selector => document.querySelector(selector);
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const safeUrl = value => { try { const url = new URL(String(value || '')); return ['http:', 'https:'].includes(url.protocol) ? url.href : ''; } catch { return ''; } };
const fa = n => new Intl.NumberFormat('fa-IR').format(Number(n) || 0);
const size = n => { n = Number(n) || 0; if (!n) return '—'; let i = 0; const units = ['B', 'KB', 'MB', 'GB', 'TB']; while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; } return `${n.toFixed(i ? 1 : 0)} ${units[i]}`; };
const labels = { home: 'خانه', catalog: 'همه بازی‌ها', consoles: 'کنسول‌ها', library: 'کتابخانه من', downloads: 'دانلودها', bios: 'فایل‌های BIOS', controls: 'دکمه‌ها', settings: 'تنظیمات', account: 'حساب من' };
const navIcons = { home: 'home', catalog: 'game', consoles: 'console', library: 'library', downloads: 'download', bios: 'bios', controls: 'game', settings: 'settings', account: 'account' };
const S = {
  section: 'home', catalog: { consoles: [], games: [] }, library: [], bios: [], settings: null, account: null,
  filter: 'all', query: '', connected: false, syncing: false, resyncRequested: false, downloads: {}, detail: null, detailId: null,
  detailCheats: [], biosLoaded: false, controlGameId: null, controlView: null, controlDirty: false, inputProfiles: [], controllerSnapshot: {status:'starting', devices:[]}, capture: null, suppressPadUntil: 0, preview: !window.__TAURI__
};
const invoke = (name, args = {}) => {
  if (!window.__TAURI__?.core?.invoke) return Promise.reject('این رابط باید در برنامهٔ دسکتاپ باز شود.');
  return window.__TAURI__.core.invoke(name, args);
};
let toastTimer;
function toast(text, error = false) {
  const el = $('#toast');
  el.textContent = String(text);
  el.className = 'toast show' + (error ? ' error' : '');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.className = 'toast'; }, 5500);
}
function activeDownloads() { return Object.values(S.downloads).filter(p => !['installed', 'failed'].includes(p.state)); }
function nav() {
  $('#nav').innerHTML = Object.keys(labels).map(k => {
    const count = k === 'library' ? S.library.length : k === 'downloads' ? activeDownloads().length : 0;
    const separator = k === 'bios' ? '<hr class="nav-divider">' : '';
    return `${separator}<button type="button" data-nav="${k}" class="${S.section === k ? 'active' : ''}" ${S.section === k ? 'aria-current="page"' : ''}>${icon(navIcons[k])}<span>${labels[k]}</span>${count ? `<span class="nav-count">${fa(count)}</span>` : ''}</button>`;
  }).join('');
  document.querySelectorAll('.top-tab').forEach(el => el.classList.toggle('active', el.dataset.nav === S.section));
  $('#crumb').textContent = labels[S.section];
  $('#side-dot').classList.toggle('online', S.connected);
  $('.head-dot').classList.toggle('online', S.connected);
  $('#side-status').textContent = S.connected ? 'متصل به کتابخانه' : 'کتابخانهٔ محلی';
  $('#connection').textContent = S.syncing ? 'در حال همگام‌سازی…' : S.connected ? 'متصل' : 'آفلاین';
  $('#sync-button').disabled = S.syncing || S.preview;
}
function title() {
  const names = {
    home: ['خوش اومدی به AMretro', 'جایی برای بازی‌هایی که هیچ‌وقت قدیمی نمی‌شن.'],
    catalog: ['همه بازی‌ها', 'بگرد، بازی مورد علاقه‌ات رو پیدا کن و شروع کن.'],
    consoles: ['کنسول‌ها', 'همهٔ کنسول‌های ثبت‌شده در پنل را ببین و بازی‌هایشان را پیدا کن.'],
    library: ['کتابخانه من', 'بازی‌های نصب‌شده روی این دستگاه، آماده برای اجرا.'],
    bios: ['فایل‌های BIOS', 'فایل‌های مورد نیاز کنسول‌ها را مدیریت کن.'],
    controls: ['تنظیم دکمه‌ها', S.controlGameId ? 'چیدمان اختصاصی همین بازی را تغییر بده.' : 'چیدمان عمومی صفحه‌کلید و دسته برای بازی‌ها.'],
    downloads: ['دانلودها', 'پیشرفت دانلود و نصب را اینجا دنبال کن.'],
    settings: ['تنظیمات', 'مسیر RetroArch و پوشه‌های بازی را بررسی کن.'],
    account: ['حساب من', 'تنظیمات بازی‌هایت را در حساب نگه دار.']
  };
  $('#heading').textContent = names[S.section][0];
  $('#subheading').textContent = names[S.section][1];
  $('#search-wrap').hidden = !['home', 'catalog', 'consoles', 'library'].includes(S.section);
  $('#eyebrow').textContent = 'AMRETRO / ' + ({ home: 'DISCOVER', catalog: 'GAMES', consoles: 'SYSTEMS', library: 'LIBRARY', downloads: 'DOWNLOADS', bios: 'BIOS', controls: 'CONTROLS', settings: 'SETTINGS', account: 'ACCOUNT' }[S.section]);
}
function empty(titleText, desc, action = '', target = 'catalog') {
  return `<div class="empty"><i>${icon('game', 24)}</i><b>${esc(titleText)}</b><p>${esc(desc)}</p>${action ? `<button class="btn light" type="button" data-nav="${esc(target)}">${esc(action)}</button>` : ''}</div>`;
}
function card(g, i) {
  const installed = S.library.some(x => x.id === g.id);
  const cover = safeUrl(g.cover_url), gameIcon = safeUrl(g.icon_url);
  const busy = S.downloads[g.id] && !['installed', 'failed'].includes(S.downloads[g.id].state);
  return `<button class="game-card" type="button" data-game="${esc(g.id)}" aria-label="جزئیات ${esc(g.title)}"><div class="cover alt${i % 3}">${cover ? `<img src="${esc(cover)}" alt="" loading="lazy">` : icon('game', 43)}${installed ? '<span class="cover-tag">نصب‌شده</span>' : busy ? '<span class="cover-tag">در حال دانلود</span>' : ''}${gameIcon ? `<img class="game-icon" src="${esc(gameIcon)}" alt="آیکون بازی">` : ''}</div><div class="game-meta"><b>${esc(g.title)}</b><small>${esc(g.console_name || 'بازی کلاسیک')}${g.size_bytes ? ' · ' + esc(size(g.size_bytes)) : ''}</small></div></button>`;
}
function matched(list) {
  const q = S.query.trim().toLocaleLowerCase();
  return list.filter(g => (S.filter === 'all' || S.filter === g.console_id) && (!q || `${g.title} ${g.console_name}`.toLocaleLowerCase().includes(q)));
}
function filters() {
  return `<div class="filters" aria-label="فیلتر کنسول"><button type="button" class="filter ${S.filter === 'all' ? 'active' : ''}" data-filter="all">همه</button>${S.catalog.consoles.map(c => `<button type="button" class="filter ${S.filter === c.id ? 'active' : ''}" data-filter="${esc(c.id)}">${safeUrl(c.icon_url) ? `<img src="${esc(safeUrl(c.icon_url))}" alt="">` : icon('console', 16)}${esc(c.name)}</button>`).join('')}</div>`;
}
function progressRows() {
  const rows = Object.values(S.downloads);
  if (!rows.length) return empty('فعلاً دانلودی نداری', 'وقتی بازی یا BIOS نصب کنی، روند آن اینجا دیده می‌شود.', 'دیدن بازی‌ها');
  return `<div class="progress-list">${rows.slice().reverse().map(p => {
    const percent = p.total ? Math.max(0, Math.min(100, Math.round(p.downloaded / p.total * 100))) : 0;
    const label = p.state === 'installed' ? 'نصب شد' : p.state === 'failed' ? 'ناموفق' : p.state === 'installing' ? 'در حال نصب' : p.state === 'completed' ? 'دانلود شد؛ در حال آماده‌سازی' : 'در حال دانلود';
    return `<div class="progress-row"><div class="progress-top"><b>${esc(p.title || p.id)}</b><small>${label}${p.total ? ' · ' + fa(percent) + '٪' : ''}</small></div><div class="bar"><i style="width:${percent}%"></i></div><div class="progress-sub"><span>${esc(p.message || '')}</span><span dir="ltr">${p.downloaded ? size(p.downloaded) + (p.total ? ' / ' + size(p.total) : '') : ''}${p.speed ? ' · ' + size(p.speed) + '/s' : ''}</span></div></div>`;
  }).join('')}</div>`;
}
function home() {
  const installed = new Set(S.library.map(g => g.id));
  const available = matched(S.catalog.games).filter(g => !installed.has(g.id));
  const games = available.slice().sort((a,b) => String(b.created_at||'').localeCompare(String(a.created_at||''))).slice(0,6);
  const weights = new Map(); S.library.forEach(g => weights.set(g.console_id, (weights.get(g.console_id)||0)+1));
  const interests = available.filter(g => weights.has(g.console_id)).sort((a,b) => (weights.get(b.console_id)||0)-(weights.get(a.console_id)||0)).slice(0,6);
  const platforms = S.catalog.consoles.slice(0, 6);
  const notice = S.preview ? '<div class="notice">این فقط پیش‌نمایش ظاهر برنامه است؛ برای نصب و اجرا برنامهٔ دسکتاپ را باز کن.</div>' : (!S.connected && S.catalog.games.length ? '<div class="notice">به پنل وصل نشدیم؛ فهرست ذخیره‌شده روی این دستگاه را می‌بینی.</div>' : '');
  return `${notice}
    <section class="hero" aria-label="معرفی AMretro">
      <div class="hero-copy"><span class="hero-kicker">یه دنیای قدیمی، یه تجربه تازه</span><h2>بازی‌های خوب،<br>هیچ‌وقت تموم نمی‌شن.</h2><p>خاطره‌انگیزترین بازی‌ها رو پیدا کن، نصب کن و دوباره به دنیای قدیم برگرد.</p><div class="hero-actions"><button type="button" class="btn primary" data-nav="catalog">${icon('game', 17)} کشف بازی‌ها ${icon('arrow', 15)}</button><button type="button" class="btn light" data-nav="library">کتابخانه من</button></div></div>
      <div class="hero-label">PLAY <span>•</span> REMEMBER <span>•</span> REPEAT</div>
    </section>
    <div class="summary-strip" aria-label="آمار کتابخانه"><div class="summary-item"><span class="summary-icon">${icon('game', 19)}</span><div><small>بازی‌ها</small><b>${fa(S.catalog.games.length)}</b></div></div><div class="summary-item"><span class="summary-icon">${icon('library', 19)}</span><div><small>نصب‌شده</small><b>${fa(S.library.length)}</b></div></div><div class="summary-item"><span class="summary-icon">${icon('console', 19)}</span><div><small>کنسول‌ها</small><b>${fa(S.catalog.consoles.length)}</b></div></div><span class="summary-hint">همه‌چیز توی یک کتابخانه</span></div>
    <div class="section-title"><div><h2>پیشنهادات</h2><p>بازی‌های منتشرشده‌ای که هنوز روی این دستگاه نصب نکرده‌ای</p></div><button type="button" data-nav="catalog">مشاهده همه ←</button></div>
    <div class="game-grid">${games.length ? games.map(card).join('') : empty(S.query ? 'چیزی پیدا نشد' : 'فعلاً پیشنهادی نداریم', S.query ? 'عبارت جست‌وجو را تغییر بده.' : 'پس از انتشار بازی در پنل و همگام‌سازی، اینجا نمایش داده می‌شود.')}</div>
    <div class="section-title"><div><h2>طبق علاقهٔ شما</h2><p>بر اساس کنسول بازی‌های نصب‌شده، فقط روی همین دستگاه</p></div></div>
    <div class="game-grid">${interests.length ? interests.map(card).join('') : empty('هنوز پیشنهاد مرتبطی نداریم', S.library.length ? 'وقتی بازی دیگری برای کنسول‌های تو منتشر شود، اینجا نشان می‌دهیم.' : 'با نصب بازی، کنسول‌های مورد علاقه‌ات را تشخیص می‌دهیم.')}</div>
    ${platforms.length ? `<div class="section-title"><div><h2>با کنسول‌ها کشف کن</h2><p>یه سفر کوتاه به دنیای مورد علاقه‌ات</p></div><button type="button" data-nav="consoles">همه کنسول‌ها ←</button></div><div class="platform-grid">${platforms.map(c => `<button type="button" class="platform-tile" data-console="${esc(c.id)}" aria-label="بازی‌های ${esc(c.name)}"><span class="platform-icon">${safeUrl(c.icon_url) ? `<img src="${esc(safeUrl(c.icon_url))}" alt="">` : icon('console', 18)}</span><b dir="auto">${esc(c.name)}</b></button>`).join('')}</div>` : ''}`;
}
function catalog(library = false) {
  const rows = matched(library ? S.library : S.catalog.games);
  return `${filters()}<div class="section-title"><div><h2>${library ? 'بازی‌های نصب‌شده' : 'کتابخانه بازی‌ها'}</h2><p>${fa(rows.length)} بازی</p></div></div><div class="game-grid">${rows.length ? rows.map(card).join('') : empty(library ? 'هنوز بازی نصب نکرده‌ای' : 'بازی‌ای پیدا نشد', library ? 'از بخش همه بازی‌ها، یک بازی را انتخاب و نصب کن.' : 'در پنل بازی اضافه کن یا جست‌وجو را تغییر بده.', library ? 'دیدن همه بازی‌ها' : '', 'catalog')}</div>`;
}
function consolesPage() {
  const q=S.query.trim().toLocaleLowerCase();
  const rows=S.catalog.consoles.filter(c=>!q||`${c.name} ${c.manufacturer||''}`.toLocaleLowerCase().includes(q));
  return `<div class="notice">این فهرست، کاتالوگ کنسول‌هاست؛ وجود نام کنسول به معنی نصب‌بودن یا سازگاری قطعی هسته نیست. RetroArch، هستهٔ مناسب و در صورت نیاز BIOS شخصی را جداگانه تهیه کن.</div><div class="section-title"><div><h2>همهٔ کنسول‌ها</h2><p>${fa(rows.length)} کنسول در پنل ثبت شده است</p></div></div><div class="all-consoles">${rows.length?rows.map(c=>`<button type="button" class="console-card" data-console="${esc(c.id)}"><span class="console-card-icon">${safeUrl(c.icon_url)?`<img src="${esc(safeUrl(c.icon_url))}" alt="">`:icon('console',24)}</span><span><b dir="auto">${esc(c.name)}</b><small>${esc(c.manufacturer||'کنسول کلاسیک')}</small><em>${c.core==='unsupported'?'در این نسخه پشتیبانی نمی‌شود':fa(S.catalog.games.filter(g=>g.console_id===c.id).length)+' بازی · هستهٔ جداگانه'}</em></span>${icon('arrow',16)}</button>`).join(''):empty('کنسولی پیدا نشد','عبارت جست‌وجو یا همگام‌سازی را بررسی کن.')}</div>`;
}
function bios() {
  if (!S.biosLoaded) return '<div class="loading"><span class="spinner"></span>در حال دریافت فهرست BIOS…</div>';
  return `<div class="notice">هنگام نصب بازی، BIOS منتشرشدهٔ همان کنسول بررسی و فقط در صورت نبود دانلود می‌شود. اگر چند BIOS منتشر کرده‌ای، همه بررسی می‌شوند. فایل متفاوت بازنویسی نمی‌شود. فقط از فایل‌هایی که حق استفاده از آن‌ها را داری استفاده کن.</div>${S.bios.length ? `<div class="row-list">${S.bios.map(b => `<div class="bios-row"><div class="row-icon">${icon('bios', 22)}</div><div class="row-info"><b>${esc(b.name)}</b><small>${esc(b.console_name)}${b.system_path ? ' · مسیر نصب: ' + esc(b.system_path) : ''}</small></div><button type="button" class="btn light" data-bios="${esc(b.id)}" ${S.downloads['bios-' + b.id] && !['installed', 'failed'].includes(S.downloads['bios-' + b.id].state) ? 'disabled' : ''}>${icon('download', 16)} نصب BIOS</button></div>`).join('')}</div>` : empty('BIOS ثبت نشده است', 'در پنل مدیریت، فایل یا لینک BIOS را اضافه و منتشر کن.')}`;
}
const CONTROL_BUTTONS = [
  ['b', 'پایین / B / ×'], ['a', 'راست / A / ○'], ['y', 'چپ / Y / □'], ['x', 'بالا / X / △'],
  ['up', 'جهت بالا'], ['down', 'جهت پایین'], ['left', 'جهت چپ'], ['right', 'جهت راست'],
  ['start', 'Start'], ['select', 'Select'], ['l', 'L1'], ['r', 'R1'], ['l2', 'L2'], ['r2', 'R2'], ['l3', 'L3'], ['r3', 'R3']
];
function keyLabel(value) {
  const names = { nul: 'تنظیم نشده', up: '↑', down: '↓', left: '←', right: '→', enter: 'Enter', rshift: 'Shift راست', shift: 'Shift چپ', space: 'Space', ctrl: 'Ctrl چپ', rctrl: 'Ctrl راست', alt: 'Alt چپ', ralt: 'Alt راست' };
  if (value?.startsWith('num') && value.length === 4) return value.slice(3);
  if (value?.startsWith('keypad')) return 'Num ' + value.slice(6);
  return names[value] || String(value || '').toUpperCase();
}
function profilesPanel() {
  const one = (kind, title) => {
    const profiles = S.inputProfiles.filter(p => p.kind === kind);
    const options = profiles.map(p => `<option value="${esc(p.id)}" ${p.active ? 'selected' : ''}>${esc(p.name)}${p.active ? ' · فعال' : ''}</option>`).join('');
    const templates = kind === 'keyboard' ? `<select id="profile-template-keyboard" aria-label="الگوی کیبورد"><option value="current">کپی چیدمان فعلی</option><option value="numpad">حرکت با Num 8 / 2 / 4 / 6</option></select>` : `<select id="profile-device-controller" aria-label="دسته برای پروفایل"><option value="">بدون اتصال به مدل خاص</option>${S.controllerSnapshot.devices.map((d,i) => `<option value="${esc(d.guid)}">${esc(d.name)} · ${fa(i+1)}</option>`).join('')}</select>`;
    return `<div class="profile-box"><h3>${title}</h3><label for="profile-select-${kind}">پروفایل ذخیره‌شده</label><select id="profile-select-${kind}">${options}</select><div class="profile-buttons"><button type="button" class="btn light" data-profile-action="activate" data-kind="${kind}">فعال‌کردن</button><button type="button" class="btn light" data-profile-action="delete" data-kind="${kind}">حذف</button></div><label for="profile-name-${kind}">نام پروفایل تازه یا نام جدید</label><input id="profile-name-${kind}" maxlength="40" placeholder="مثلاً ${kind === 'keyboard' ? 'کیبورد ۲' : 'دستهٔ آبی'}">${templates}<div class="profile-buttons"><button type="button" class="btn primary" data-profile-action="create" data-kind="${kind}">ساخت و فعال‌کردن</button><button type="button" class="btn light" data-profile-action="rename" data-kind="${kind}">تغییر نام انتخاب‌شده</button></div></div>`;
  };
  const devices = S.controllerSnapshot.devices;
  let discovery = S.controllerSnapshot.status === 'unavailable' ? 'شناسایی دسته در این سیستم در دسترس نیست؛ درایورها و دسترسی دستگاه را بررسی کن.' : S.controllerSnapshot.status === 'starting' ? 'در حال جست‌وجوی دسته‌ها…' : devices.length ? devices.map(d => `<li><b>${esc(d.name)}</b><span dir="ltr">${esc(d.guid)}</span></li>`).join('') : 'هنوز دسته‌ای شناسایی نشده است؛ با USB یا بلوتوث به سیستم وصل کن.';
  if (devices.length) discovery = `<ul class="device-list">${discovery}</ul>`;
  return `<section class="panel profiles-panel"><div class="controls-head"><div><h2>پروفایل‌ها و دسته‌ها</h2><p>کیبورد و دسته جدا ذخیره می‌شوند. با فعال‌کردن هرکدام، فقط همان بخش عوض می‌شود.</p></div><button type="button" class="btn light" data-profile-action="refresh">تازه‌سازی دسته‌ها</button></div><div class="discovery">${discovery}</div><p class="controls-help">دسته را ابتدا به سیستم وصل کن. شناسه نشان‌دهندهٔ مدل است و دستگاه‌های یکسان ممکن است یک شناسه داشته باشند. انتخاب پروفایل، جای دکمه‌های منطقی RetroPad را تغییر می‌دهد؛ انتخاب دستگاه فیزیکی و تنظیم آنالوگ در RetroArch انجام می‌شود. همهٔ این تنظیمات برای بازیکن ۱ هستند.</p><div class="profiles-grid">${one('keyboard','⌨ پروفایل کیبورد')}${one('controller','🎮 پروفایل دسته')}</div></section>`;
}
function controlsPage() {
  if (!S.controlView) return '<div class="loading"><span class="spinner"></span>در حال دریافت دکمه‌ها…</div>';
  const p = S.controlView.profile, game = S.library.find(x => x.id === S.controlGameId);
  const scope = S.controlGameId ? `تنظیم اختصاصی ${esc(game?.title || S.controlGameId)}` : 'تنظیم عمومی همهٔ بازی‌ها';
  const status = S.controlView.customized ? 'چیدمان ذخیره شده است.' : S.controlGameId && S.controlView.inherited ? 'فعلاً تنظیم عمومی استفاده می‌شود؛ با ذخیره، یک نسخهٔ مستقل برای این بازی ساخته می‌شود.' : 'چیدمان پیش‌فرض WASD هنگام اجرای بازی از AMretro اعمال می‌شود.';
  const options = (chosen) => CONTROL_BUTTONS.map(([id, label]) => `<option value="${id}" ${id === chosen ? 'selected' : ''}>${esc(label)}</option>`).join('');
  const rows = CONTROL_BUTTONS.map(([id, label]) => {
    const waitingKey = S.capture?.kind === 'key' && S.capture.button === id;
    const waitingPad = S.capture?.kind === 'pad' && S.capture.button === id;
    return `<div class="control-row"><b dir="auto">${esc(label)}</b><button type="button" class="bind-key ${waitingKey ? 'waiting' : ''}" data-bind-key="${id}" aria-label="کلید صفحه‌کلید برای ${esc(label)}">${waitingKey ? 'کلید را فشار بده…' : esc(keyLabel(p.keyboard[id]))}</button><div class="pad-bind"><select data-bind-pad="${id}" aria-label="دکمه دسته برای ${esc(label)}">${options(p.gamepad[id])}</select><button type="button" class="pad-capture ${waitingPad ? 'waiting' : ''}" data-capture-pad="${id}" title="دکمه موردنظر دسته را فشار بده">${waitingPad ? 'منتظر دسته…' : 'فشار دسته'}</button></div></div>`;
  }).join('');
  return `${S.controlGameId ? '<div class="notice">تنظیم اختصاصی این بازی بر پروفایل‌های عمومی اولویت دارد.</div>' : profilesPanel()}
    <section class="panel controls-panel"><div class="controls-head"><div><h2>${scope}</h2><p>${esc(status)}${S.controlDirty ? ' · تغییرات ذخیره نشده‌اند.' : ''}</p></div><div class="control-actions"><button type="button" class="btn primary" data-controls-action="save">ذخیره دکمه‌ها</button><button type="button" class="btn light" data-controls-action="reset">${S.controlGameId ? 'بازگشت به تنظیم عمومی' : 'بازنشانی پروفایل‌های فعال'}</button>${S.controlGameId ? '<button type="button" class="btn light" data-controls-action="global">تنظیم عمومی</button>' : ''}</div></div>
    <p class="controls-help">روی کلید صفحه‌کلید بزن و کلید جدید را فشار بده؛ برای دسته هم از فهرست انتخاب کن یا «فشار دسته» را بزن. هنگام انتخاب تکراری، جای دو دکمه عوض می‌شود. تغییرات از اجرای بعدی اعمال می‌شوند.</p>
    <div class="control-row control-header"><span>دکمه در بازی</span><span>صفحه‌کلید</span><span>دکمهٔ دسته (RetroPad)</span></div><div class="control-list">${rows}</div>
    <div class="control-actions bottom"><button type="button" class="btn primary" data-controls-action="save">ذخیره دکمه‌ها</button>${S.capture ? '<button type="button" class="btn light" data-controls-action="cancel">لغو انتظار</button>' : ''}</div></section>`;
}
async function loadControls() {
  const id = S.controlGameId; S.controlView = null; render();
  try {
    const [result, profiles, devices] = await Promise.all([invoke('get_controls', { gameId: id }), invoke('list_input_profiles'), invoke('list_controllers')]);
    if (S.section === 'controls' && S.controlGameId === id) { S.controlView = result; S.inputProfiles = profiles; S.controllerSnapshot = devices; S.controlDirty = false; render(); }
  } catch (e) { toast('دریافت تنظیم دکمه‌ها ممکن نشد: ' + e, true); }
}
async function profileAction(action, kind) {
  if (action === 'refresh') { try { S.controllerSnapshot = await invoke('list_controllers'); render(); toast('فهرست دسته‌ها تازه شد.'); } catch(e) { toast(e,true); } return; }
  if (!S.controlView || !['keyboard','controller'].includes(kind)) return;
  const id = $(`#profile-select-${kind}`)?.value, name = $(`#profile-name-${kind}`)?.value || '';
  try {
    if (action === 'activate') {
      if (S.controlDirty && !confirm('تغییرات ذخیره نشده‌اند و با انتخاب پروفایل از دست می‌روند. ادامه می‌دهی؟')) return;
      await invoke('activate_input_profile', {kind,id});
    } else if (action === 'create') {
      const template = kind === 'keyboard' ? $('#profile-template-keyboard').value : 'current';
      const deviceGuid = kind === 'controller' ? $('#profile-device-controller').value : '';
      await invoke('create_input_profile', {kind,name,template,deviceGuid,current:S.controlView.profile});
    } else if (action === 'rename') {
      await invoke('rename_input_profile', {id,name}); S.inputProfiles = await invoke('list_input_profiles'); render(); toast('نام پروفایل تغییر کرد.'); return;
    } else if (action === 'delete') {
      if (!confirm('پروفایل انتخاب‌شده حذف شود؟' + (S.controlDirty ? ' تغییرات ذخیره‌نشدهٔ فعلی نیز کنار گذاشته می‌شوند.' : ''))) return;
      await invoke('delete_input_profile', {kind,id});
    } else return;
    S.capture = null; await loadControls(); toast('پروفایل‌ها ذخیره شدند. تغییرات از اجرای بعدی بازی اعمال می‌شوند.');
  } catch(e) { toast(e,true); }
}
function openGameControls(id) {
  if (!S.library.some(g => g.id === id)) return;
  closeDialog(); S.section = 'controls'; S.controlGameId = id; S.controlView = null; S.capture = null; render(); loadControls();
}
function swapPad(target, source) {
  if (!S.controlView || !CONTROL_BUTTONS.some(([id]) => id === source)) return;
  const pad = S.controlView.profile.gamepad, old = pad[target];
  const other = Object.keys(pad).find(k => k !== target && pad[k] === source);
  if (other) pad[other] = old;
  pad[target] = source; S.capture = null; S.controlDirty = true; render();
}
async function controlAction(action) {
  if (action === 'global') { await navigate('controls'); return; }
  if (action === 'cancel') { S.capture = null; render(); return; }
  if (!S.controlView) return;
  try {
    if (action === 'save') {
      await invoke('save_controls', { gameId: S.controlGameId, profile: S.controlView.profile });
      S.controlView.customized = true; S.controlView.inherited = false; S.controlDirty = false; S.capture = null; render(); toast('دکمه‌ها ذخیره شدند؛ در اجرای بعدی اعمال می‌شوند.');
    } else if (action === 'reset') {
      if (!S.controlGameId && !confirm('پروفایل‌های فعال کیبورد و دسته به تنظیمات پیش‌فرض برگردند؟')) return;
      await invoke('reset_controls', { gameId: S.controlGameId }); S.capture = null; await loadControls(); toast(S.controlGameId ? 'تنظیم این بازی به حالت عمومی برگشت.' : 'پروفایل‌های فعال به پیش‌فرض برگشتند.');
    }
  } catch (e) { toast(e, true); }
}
function retroKey(code) {
  if (/^Key[A-Z]$/.test(code)) return code.slice(3).toLowerCase();
  if (/^Digit[0-9]$/.test(code)) return 'num' + code.slice(5);
  if (/^Numpad[0-9]$/.test(code)) return 'keypad' + code.slice(6);
  return ({ ArrowUp:'up', ArrowDown:'down', ArrowLeft:'left', ArrowRight:'right', Enter:'enter', NumpadEnter:'kp_enter', Space:'space', ShiftLeft:'shift', ShiftRight:'rshift', ControlLeft:'ctrl', ControlRight:'rctrl', AltLeft:'alt', AltRight:'ralt', Tab:'tab', Backspace:'backspace', Insert:'insert', Delete:'del', End:'end', Home:'home', PageUp:'pageup', PageDown:'pagedown', Period:'period', Quote:'quote', Comma:'comma', Minus:'minus', Slash:'slash', Semicolon:'semicolon', Equal:'equals', BracketLeft:'leftbracket', Backslash:'backslash', BracketRight:'rightbracket', Backquote:'backquote', NumpadDecimal:'kp_period', NumpadAdd:'kp_plus', NumpadSubtract:'kp_minus', NumpadMultiply:'multiply', NumpadDivide:'divide' })[code] || '';
}

function settings() {
  if (!S.settings) return '<div class="loading"><span class="spinner"></span>در حال دریافت تنظیمات…</div>';
  const s = S.settings;
  return `<div class="notice">ابتدا RetroArch و هسته‌های مربوط به کنسول‌ها را نصب کن. این مسیرها در دستگاه خودت ذخیره می‌شوند.</div><form id="settings-form" class="panel settings-grid"><div class="field full"><label for="api_url">آدرس پنل و API</label><input id="api_url" name="api_url" dir="ltr" value="${esc(s.api_url)}" required><small>آدرس سروری که بازی‌ها از آن خوانده می‌شوند.</small></div><div class="field full"><label for="games_dir">پوشهٔ بازی‌ها</label><input id="games_dir" name="games_dir" dir="ltr" value="${esc(s.games_dir)}" required><small>مسیر کامل محل نصب بازی‌ها.</small></div><div class="field full"><label for="retroarch_path">برنامهٔ RetroArch</label><input id="retroarch_path" name="retroarch_path" dir="ltr" value="${esc(s.retroarch_path)}" required><small>نام برنامه در PATH یا مسیر کامل فایل اجرایی.</small></div><div class="field"><label for="cores_dir">پوشهٔ هسته‌ها (cores)</label><input id="cores_dir" name="cores_dir" dir="ltr" value="${esc(s.cores_dir)}" required></div><div class="field"><label for="system_dir">پوشهٔ BIOS (system)</label><input id="system_dir" name="system_dir" dir="ltr" value="${esc(s.system_dir)}" required></div><div class="settings-actions"><button class="btn primary" type="submit">ذخیره تنظیمات</button></div></form>`;
}
function account() {
  if (S.account) return `<div class="panel account"><h2>وارد حساب شده‌ای</h2><p>ایمیل: <b dir="ltr">${esc(S.account.email)}</b><br>حالت بازی‌ها بین دستگاه و حساب همگام می‌شود. مسیر فایل‌های دستگاه ارسال نمی‌شود.</p><div class="account-actions"><button type="button" class="btn primary" data-account="sync">همگام‌سازی تنظیمات</button><button type="button" class="btn light" data-account="logout">خروج از حساب</button></div><p>برای امنیت، ورود فقط تا بسته‌شدن برنامه نگه داشته می‌شود.</p></div>`;
  return `<div class="panel account"><h2>ورود به حساب</h2><p>ورود اختیاری است. با حساب، حالت عملکرد بازی‌ها در Supabase ذخیره می‌شود؛ فایل و تصویر آنجا ذخیره نمی‌شود.</p><form class="account-form" id="account-form"><div class="field"><label for="email">ایمیل</label><input id="email" name="email" type="email" dir="ltr" required placeholder="you@example.com" autocomplete="username"></div><div class="field"><label for="password">رمز عبور</label><input id="password" name="password" type="password" dir="ltr" minlength="6" required autocomplete="current-password"></div><div class="account-actions"><button class="btn primary" type="submit">ورود</button><button class="btn light" type="button" data-account="signup">ساخت حساب</button></div></form><p>برای ساخت حساب باید ایمیلت را تأیید کنی. اگر ایمیل تأیید نمی‌رسد، مدیر باید سرویس ارسال ایمیل را در Supabase تنظیم کند.</p></div>`;
}
function dock() {
  const active = activeDownloads(), el = $('#download-dock');
  el.hidden = !active.length;
  if (!active.length) return;
  const p = active[0], percent = p.total ? Math.max(0, Math.min(100, Math.round(p.downloaded / p.total * 100))) : 0;
  el.innerHTML = `${icon('download', 17)} <span>${esc(p.title || 'دانلود')} ${p.total ? '· ' + fa(percent) + '٪' : '· در حال آماده‌سازی'}</span><div class="bar"><i style="width:${percent}%"></i></div><button type="button" data-nav="downloads">مشاهده دانلودها</button>`;
}
function render() {
  nav(); title();
  $('#content').innerHTML = S.section === 'home' ? home() : S.section === 'catalog' ? catalog() : S.section === 'consoles' ? consolesPage() : S.section === 'library' ? catalog(true) : S.section === 'bios' ? bios() : S.section === 'downloads' ? progressRows() : S.section === 'controls' ? controlsPage() : S.section === 'settings' ? settings() : account();
  dock();
}
function progressDetail(g) {
  const p = S.downloads[g.id]; if (!p) return '';
  const percent = p.total ? Math.max(0, Math.min(100, Math.round(p.downloaded / p.total * 100))) : 0;
  return `<div class="progress-row"><div class="progress-top"><b>${esc(p.message || 'در حال انجام…')}</b><small>${p.total ? fa(percent) + '٪' : ''}</small></div><div class="bar"><i style="width:${percent}%"></i></div></div>`;
}
function detail() {
  const g = S.detail; if (!g) return;
  const installed = S.library.find(x => x.id === g.id), cover = safeUrl(g.cover_url), gameIcon = safeUrl(g.icon_url);
  const active = S.downloads[g.id] && !['installed', 'failed'].includes(S.downloads[g.id].state);
  const system = S.catalog.consoles.find(c => c.id === g.console_id), unsupported = g.console_core === 'unsupported' || system?.core === 'unsupported';
  $('#dialog-title').textContent = 'جزئیات بازی';
  $('#dialog-content').innerHTML = `<div class="detail-layout"><div class="detail-cover">${cover ? `<img src="${esc(cover)}" alt="جلد بازی">` : icon('game', 48)}</div><div class="detail-info">${gameIcon ? `<img class="detail-icon" src="${esc(gameIcon)}" alt="آیکون بازی">` : ''}<h3>${esc(g.title)}</h3><div class="platform">${esc(g.console_name || 'بازی کلاسیک')}${g.size_bytes ? ' · ' + esc(size(g.size_bytes)) : ''}</div><p>${esc(g.description || 'برای شروع، بازی را روی این دستگاه نصب کن.')}</p><div class="detail-actions">${installed ? `<button type="button" class="btn primary" data-launch="${esc(g.id)}">${icon('play', 16)} اجرای بازی</button><button type="button" class="btn light" data-control-game="${esc(g.id)}">${icon('game', 16)} تنظیم دکمه‌های بازی</button>` : unsupported ? `<span class="notice">این کنسول در نسخهٔ فعلی AMretro پشتیبانی نمی‌شود.</span>` : `<button type="button" class="btn primary" data-install="${esc(g.id)}" ${active ? 'disabled' : ''}>${icon('download', 16)} ${active ? 'در حال نصب' : 'دانلود و نصب'}</button>`}<button type="button" class="btn light" data-nav="downloads">دانلودها</button></div>${installed ? `<div class="preset"><label for="preset">حالت اجرای این بازی</label><select id="preset" data-preset="${esc(g.id)}"><option value="performance" ${installed.preset === 'performance' ? 'selected' : ''}>عملکرد بیشتر</option><option value="balanced" ${installed.preset === 'balanced' ? 'selected' : ''}>متعادل</option><option value="quality" ${installed.preset === 'quality' ? 'selected' : ''}>کیفیت بیشتر</option></select></div>` : ''}${progressDetail(g)}</div><div class="detail-extra">${g.images?.length ? `<h4>تصاویر بازی</h4><div class="gallery">${g.images.filter(i => safeUrl(i.url)).map(i => `<img src="${esc(safeUrl(i.url))}" alt="${esc(i.alt_text || 'تصویر بازی')}" loading="lazy">`).join('')}</div>` : ''}${S.detailCheats.length ? `<h4>کدهای تقلب</h4>${S.detailCheats.map(c => `<div class="cheat"><b>${esc(c.name)}</b><pre>${esc(c.code)}</pre></div>`).join('')}` : ''}</div></div>`;
}
async function openGame(id) {
  S.detailId = id;
  S.detail = S.catalog.games.find(x => x.id === id) || S.library.find(x => x.id === id) || null;
  S.detailCheats = [];
  $('#overlay').classList.add('open'); $('#overlay').setAttribute('aria-hidden', 'false');
  $('#dialog-content').innerHTML = '<div class="loading"><span class="spinner"></span>در حال دریافت اطلاعات بازی…</div>';
  $('#close-dialog').focus();
  const [game, cheats] = await Promise.allSettled([invoke('game_details', { gameId: id }), invoke('list_cheats', { gameId: id })]);
  if (S.detailId !== id) return;
  if (game.status === 'fulfilled') S.detail = game.value;
  if (cheats.status === 'fulfilled') S.detailCheats = cheats.value;
  if (S.detail) detail(); else $('#dialog-content').innerHTML = '<div class="loading">اطلاعات بازی پیدا نشد.</div>';
}
function closeDialog() { S.detailId = null; S.detail = null; $('#overlay').classList.remove('open'); $('#overlay').setAttribute('aria-hidden', 'true'); }
async function sync(silent = false) {
  if (S.preview) return;
  if (S.syncing) { S.resyncRequested = true; return; }
  S.syncing = true; nav();
  try { S.catalog = await invoke('sync_catalog'); S.library = await invoke('list_library'); S.connected = true; if (!silent) toast('کتابخانه به‌روز شد.'); }
  catch (e) { S.connected = false; if (!silent) toast('اتصال به پنل ممکن نشد: ' + e, true); }
  finally { S.syncing = false; render(); if (S.resyncRequested) { S.resyncRequested = false; await sync(true); } }
}
async function loadBios() {
  S.biosLoaded = false; if (S.section === 'bios') render();
  try { S.bios = await invoke('list_bios'); S.biosLoaded = true; }
  catch (e) { S.biosLoaded = true; toast('دریافت BIOS ممکن نشد: ' + e, true); }
  if (S.section === 'bios') render();
}
async function startInstall(id, kind) {
  const item = kind === 'bios' ? S.bios.find(x => x.id === id) : S.catalog.games.find(x => x.id === id);
  const key = kind === 'bios' ? 'bios-' + id : id; if (!item) return;
  S.downloads[key] = { id, key, kind, title: item.title || item.name, state: 'starting', message: 'در حال آماده‌سازی…' };
  render(); if (S.detailId === id) detail();
  try { await invoke(kind === 'bios' ? 'start_bios_install' : 'start_install', kind === 'bios' ? { biosId: id } : { gameId: id }); }
  catch (e) { S.downloads[key].state = 'failed'; S.downloads[key].message = String(e); toast(e, true); render(); if (S.detailId === id) detail(); }
}
async function launch(id) { try { await invoke('launch_game', { gameId: id }); toast('RetroArch اجرا شد.'); } catch (e) { toast(e, true); } }
function receiveProgress(p) {
  const key = p.kind === 'bios' ? 'bios-' + p.id : p.id;
  const old = S.downloads[key] || { title: (p.kind === 'bios' ? S.bios.find(x => x.id === p.id)?.name : S.catalog.games.find(x => x.id === p.id)?.title) || p.id };
  S.downloads[key] = { ...old, ...p };
  if (p.state === 'failed') toast(p.message, true);
  if (p.state === 'installed') { toast(p.message); invoke('list_library').then(x => { S.library = x; render(); if (S.detailId === p.id) detail(); }).catch(() => {}); }
  if (['downloads', 'catalog', 'library', 'bios', 'home'].includes(S.section)) render(); else dock();
  if (S.detailId === p.id) detail();
}
async function navigate(k) {
  if (!labels[k]) return;
  closeDialog(); if (k === 'controls') { S.controlGameId = null; S.controlView = null; S.capture = null; } S.section = k; S.filter = 'all'; S.query = ''; $('#search').value = ''; render();
  if (k === 'bios') loadBios();
  if (k === 'controls') loadControls();
  if (k === 'settings' && !S.settings) { try { S.settings = await invoke('get_settings'); if (S.section === 'settings') render(); } catch (e) { toast(e, true); } }
}
document.addEventListener('click', async e => {
  const x = e.target.closest('[data-nav],[data-console],[data-game],[data-filter],[data-install],[data-bios],[data-launch],[data-account],[data-control-game],[data-controls-action],[data-bind-key],[data-capture-pad],[data-profile-action]'); if (!x) return;
  if (x.dataset.nav) { navigate(x.dataset.nav); return; }
  if (x.dataset.console) { await navigate('catalog'); S.filter = x.dataset.console; render(); return; }
  if (x.dataset.filter) { S.filter = x.dataset.filter; render(); return; }
  if (x.dataset.game) { openGame(x.dataset.game); return; }
  if (x.dataset.install) { startInstall(x.dataset.install, 'game'); return; }
  if (x.dataset.controlGame) { openGameControls(x.dataset.controlGame); return; }
  if (x.dataset.profileAction) { await profileAction(x.dataset.profileAction, x.dataset.kind); return; }
  if (x.dataset.bindKey) { S.capture = { kind: 'key', button: x.dataset.bindKey }; render(); return; }
  if (x.dataset.capturePad) { S.capture = { kind: 'pad', button: x.dataset.capturePad }; render(); return; }
  if (x.dataset.controlsAction) { await controlAction(x.dataset.controlsAction); return; }
  if (x.dataset.bios) { startInstall(x.dataset.bios, 'bios'); return; }
  if (x.dataset.launch) { launch(x.dataset.launch); return; }
  if (x.dataset.account === 'logout') { try { await invoke('account_sign_out'); S.account = null; render(); toast('از حساب خارج شدی.'); } catch (err) { toast(err, true); } }
  if (x.dataset.account === 'sync') { try { await invoke('sync_account'); S.library = await invoke('list_library'); render(); toast('تنظیمات حساب همگام شد.'); } catch (err) { toast(err, true); } }
  if (x.dataset.account === 'signup') { const f = $('#account-form'); if (!f.reportValidity()) return; try { toast(await invoke('account_sign_up', { email: f.email.value, password: f.password.value })); } catch (err) { toast(err, true); } }
});
document.addEventListener('submit', async e => {
  if (e.target.id === 'settings-form') { e.preventDefault(); const obj = Object.fromEntries(new FormData(e.target)); try { await invoke('save_settings', { settings: obj }); S.settings = obj; toast('تنظیمات ذخیره شد.'); sync(true); } catch (err) { toast(err, true); } }
  if (e.target.id === 'account-form') { e.preventDefault(); const f = e.target; try { S.account = await invoke('account_sign_in', { email: f.email.value, password: f.password.value }); render(); try { await invoke('sync_account'); S.library = await invoke('list_library'); render(); toast('وارد شدی؛ تنظیمات حساب همگام شد.'); } catch (err) { toast('ورود انجام شد؛ همگام‌سازی تنظیمات: ' + err, true); } } catch (err) { toast(err, true); } }
});
document.addEventListener('change', async e => {
  if (e.target.matches('[data-bind-pad]')) { swapPad(e.target.dataset.bindPad, e.target.value); return; }
  if (!e.target.matches('[data-preset]')) return;
  const id = e.target.dataset.preset, preset = e.target.value;
  try { await invoke('set_game_preset', { gameId: id, preset }); const item = S.library.find(g => g.id === id); if (item) item.preset = preset; toast('حالت بازی ذخیره شد.'); } catch (err) { toast(err, true); }
});
document.addEventListener('error', e => { if (e.target.tagName === 'IMG') e.target.classList.add('error-image'); }, true);
$('#search').addEventListener('input', e => { S.query = e.target.value; render(); });
$('#sync-button').onclick = () => sync();
$('#close-dialog').onclick = closeDialog;
$('#overlay').onclick = e => { if (e.target.id === 'overlay') closeDialog(); };
document.addEventListener('keydown', e => {
  if (S.section === 'controls' && S.capture?.kind === 'key') {
    e.preventDefault(); e.stopPropagation();
    if (e.key === 'Escape') { S.capture = null; render(); return; }
    const value = retroKey(e.code);
    if (!value) { toast('این کلید پشتیبانی نمی‌شود؛ کلید دیگری انتخاب کن.', true); return; }
    const target = S.capture.button, keys = S.controlView.profile.keyboard, previous = keys[target];
    const other = Object.keys(keys).find(k => k !== target && keys[k] === value && value !== 'nul');
    if (other) keys[other] = previous;
    keys[target] = value; S.capture = null; S.controlDirty = true; render(); return;
  }
  if (e.key === 'Escape') { if (S.capture) { S.capture = null; render(); } else closeDialog(); }
});
async function init() {
  render(); if (S.preview) return;
  try {
    await window.__TAURI__.event.listen('download-progress', event => receiveProgress(event.payload));
    await window.__TAURI__.event.listen('gamepad-bind', event => {
      if (S.section !== 'controls' || S.capture?.kind !== 'pad') return;
      const payload = typeof event.payload === 'string' ? {binding:event.payload,guid:''} : event.payload;
      const selected = S.inputProfiles.find(p => p.kind === 'controller' && p.active);
      if (!S.controlGameId && selected?.device_guid && payload.guid !== selected.device_guid) { toast('دکمه را روی دستهٔ مرتبط با پروفایل فعال فشار بده.', true); return; }
      const target = S.capture.button; S.capture = null; S.suppressPadUntil = Date.now() + 350;
      swapPad(target, payload.binding);
    });
    await window.__TAURI__.event.listen('gamepads-changed', async () => {
      try { S.controllerSnapshot = await invoke('list_controllers');
        if (S.section === 'controls' && !S.controlGameId && !S.capture && !['INPUT','SELECT','TEXTAREA'].includes(document.activeElement?.tagName)) render();
      } catch (_) { /* manual refresh can report the error */ }
    });
    await window.__TAURI__.event.listen('gamepad-input', event => {
      if (Date.now() < S.suppressPadUntil || S.capture) return;
      const action = event.payload;
      if (action === 'back') { closeDialog(); return; }
      if (document.activeElement?.matches('input,textarea,select')) return;
      const scope = $('#overlay').classList.contains('open') ? $('#overlay') : document;
      const controls = Array.from(scope.querySelectorAll('button:not([disabled]),a[href],select')).filter(el => el.offsetParent !== null);
      const i = controls.indexOf(document.activeElement);
      if (action === 'activate') { if (i >= 0) controls[i].click(); }
      else if (['left', 'up', 'right', 'down'].includes(action) && controls.length) { const step = ['left', 'up'].includes(action) ? -1 : 1; controls[(i + step + controls.length) % controls.length].focus(); }
    });
  } catch (e) { console.warn('Event listener unavailable', e); }
  const [settings, library, cached, accountStatus] = await Promise.allSettled([invoke('get_settings'), invoke('list_library'), invoke('get_cached_catalog'), invoke('account_status')]);
  if (settings.status === 'fulfilled') S.settings = settings.value;
  if (library.status === 'fulfilled') S.library = library.value;
  if (cached.status === 'fulfilled' && cached.value) S.catalog = cached.value;
  if (accountStatus.status === 'fulfilled') S.account = accountStatus.value;
  render(); await sync(true);
}
init();
