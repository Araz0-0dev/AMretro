import { Hono } from 'hono';
import { z } from 'zod';
import adminHtml from './admin.generated';

type Env = {
  DB: D1Database;
  MEDIA_DB?: D1Database;
  ADMIN_USER?: string;
  ADMIN_PASSWORD?: string;
  SUPABASE_URL?: string;
  SUPABASE_SECRET_KEY?: string;
  SUPABASE_PUBLISHABLE_KEY?: string;
  LOCAL_PREVIEW?: string;
};
type AppContext = { Bindings: Env };
const app = new Hono<AppContext>();

app.use('*', async (c, next) => {
  await next();
  c.header('X-Content-Type-Options', 'nosniff');
  c.header('Referrer-Policy', 'no-referrer');
  if (!(c.env.LOCAL_PREVIEW === 'true' && c.req.path === '/preview')) c.header('X-Frame-Options', 'DENY');
});
app.use('/api/*', async (c, next) => {
  if (!c.req.path.startsWith('/api/admin/')) {
    c.header('Access-Control-Allow-Origin', '*');
    c.header('Access-Control-Allow-Methods', 'GET, HEAD, OPTIONS');
    c.header('Access-Control-Allow-Headers', 'Content-Type, Range');
    c.header('Access-Control-Expose-Headers', 'Content-Length, Content-Range, ETag, Accept-Ranges');
    if (c.req.method === 'OPTIONS') return c.body(null, 204);
  }
  await next();
});

async function safeEquals(a: string, b: string): Promise<boolean> {
  const enc = new TextEncoder();
  const [x, y] = await Promise.all([
    crypto.subtle.digest('SHA-256', enc.encode(a)),
    crypto.subtle.digest('SHA-256', enc.encode(b)),
  ]);
  const xa = new Uint8Array(x), ya = new Uint8Array(y);
  let diff = 0;
  for (let i = 0; i < xa.length; i++) diff |= xa[i] ^ ya[i];
  return diff === 0;
}

async function adminAuth(c: any, next: any) {
  c.header('Cache-Control', 'no-store');
  const user = c.env.ADMIN_USER || 'admin';
  const password = c.env.ADMIN_PASSWORD;
  if (!password) return c.json({ error: 'مدیریت هنوز تنظیم نشده است.' }, 503);
  const auth = c.req.header('Authorization') || '';
  let incoming = '';
  if (/^Basic\s+/i.test(auth)) {
    try { incoming = atob(auth.replace(/^Basic\s+/i, '')); } catch { incoming = ''; }
  }
  const split = incoming.indexOf(':');
  const suppliedUser = split >= 0 ? incoming.slice(0, split) : '';
  const suppliedPass = split >= 0 ? incoming.slice(split + 1) : '';
  const [u, p] = await Promise.all([safeEquals(suppliedUser, user), safeEquals(suppliedPass, password)]);
  if (!u || !p) {
    c.header('WWW-Authenticate', 'Basic realm="Retro Hub Admin", charset="UTF-8"');
    return c.json({ error: 'ورود مدیریتی لازم است.' }, 401);
  }
  const method = c.req.method;
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    const origin = c.req.header('Origin');
    if (origin && origin !== new URL(c.req.url).origin) return c.json({ error: 'مبدأ درخواست مجاز نیست.' }, 403);
    if (c.req.header('Sec-Fetch-Site') === 'cross-site') return c.json({ error: 'درخواست بین‌سایتی مجاز نیست.' }, 403);
    const binaryUpload = ['/api/admin/uploads/image','/api/admin/uploads/file'].includes(c.req.path) && method === 'POST';
    if (binaryUpload) {
      if (!origin || origin !== new URL(c.req.url).origin || c.req.header('X-Retro-Upload') !== '1') return c.json({ error: 'درخواست آپلود نامعتبر است.' }, 403);
    } else if (!(c.req.header('Content-Type') || '').toLowerCase().startsWith('application/json')) {
      return c.json({ error: 'درخواست باید JSON باشد.' }, 415);
    }
  }
  await next();
}
app.use('/admin', adminAuth);
app.use('/api/admin/*', adminAuth);
app.get('/', (c) => c.redirect(c.env.LOCAL_PREVIEW === 'true' ? '/preview' : '/admin', 302));
app.get('/preview', (c) => {
  if (c.env.LOCAL_PREVIEW !== 'true') return c.notFound();
  const demoScript = `<script>const originalFetch=window.fetch; window.fetch=(url,options={})=>{const target=String(url);if(target.startsWith('/api/admin/')){const headers=new Headers(options.headers||{});headers.set('Authorization','Basic '+btoa('admin:local-preview-only'));options={...options,headers}}return originalFetch(url,options)};</script>`;
  const demoBanner = '<div style="background:#eee9ff;color:#514098;padding:9px 14px;text-align:center;font:12px Tahoma,Arial;line-height:1.5">پیش‌نمایش محلی با داده‌های آزمایشی؛ هنوز روی حساب Cloudflare مستقر نشده است.</div>';
  c.header('Content-Security-Policy', "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' https: data: blob:; connect-src 'self'; base-uri 'none'; form-action 'self'");
  c.header('Cache-Control', 'no-store');
  return c.html(adminHtml.replace('</head>', demoScript + '</head>').replace('<body>', '<body>' + demoBanner));
});
app.get('/admin', (c) => {
  c.header('Content-Security-Policy', "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' https: data: blob:; connect-src 'self' https://*.supabase.co; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
  return c.html(adminHtml);
});
app.get('/api/health', (c) => c.json({ ok: true, service: 'retro-hub', version: '0.1.0' }));

const rowWithUrl = (row: any, origin: string, type: 'games' | 'bios') => {
  const { storage_key: _storageKey, cover_key: coverKey, icon_key: iconKey, file_provider: fileProvider, ...publicRow } = row;
  const provider = fileProvider || row.source;
  return {
    ...publicRow, source: provider,
    ...(type === 'games' ? { cover_url: coverKey ? `${origin}/api/covers/${encodeURIComponent(row.id)}` : row.cover_url, icon_url: iconKey ? `${origin}/api/icons/games/${encodeURIComponent(row.id)}` : row.icon_url } : {}),
    download_url: provider === 'external' ? row.external_url : `${origin}/api/files/${type}/${encodeURIComponent(row.id)}`,
  };
};
const imageWithUrl = (row: any, origin: string, admin = false) => ({
  id: row.id, game_id: row.game_id, alt_text: row.alt_text, sort_order: row.sort_order,
  url: row.storage_key ? `${origin}/api/${admin ? 'admin/' : ''}images/${encodeURIComponent(row.id)}` : row.image_url,
});

app.get('/api/consoles', async (c) => {
  const out = await c.env.DB.prepare('SELECT * FROM consoles ORDER BY name COLLATE NOCASE').all();
  const origin = new URL(c.req.url).origin;
  return c.json({ data: out.results.map((row: any) => { const {icon_key, ...rest} = row; return {...rest, icon_url:icon_key ? `${origin}/api/icons/consoles/${encodeURIComponent(row.id)}` : row.icon_url}; }) });
});
app.get('/api/games', async (c) => {
  const page = Math.max(1, Math.min(100000, Number.parseInt(c.req.query('page') || '1', 10) || 1));
  const limit = Math.max(1, Math.min(100, Number.parseInt(c.req.query('limit') || '50', 10) || 50));
  const where = ['g.published = 1'];
  const args: (string | number)[] = [];
  const consoleId = c.req.query('console_id');
  const search = c.req.query('q')?.trim().slice(0, 120);
  if (consoleId) { where.push('g.console_id = ?'); args.push(consoleId); }
  if (search) { where.push("g.title LIKE ? ESCAPE '\\'"); args.push('%' + search.replace(/[\\%_]/g, '\\$&') + '%'); }
  const clause = where.join(' AND ');
  const count = await c.env.DB.prepare(`SELECT COUNT(*) AS n FROM games g WHERE ${clause}`).bind(...args).first<{n: number}>();
  const rows = await c.env.DB.prepare(`SELECT g.*, c.name AS console_name, c.core AS console_core FROM games g JOIN consoles c ON c.id=g.console_id WHERE ${clause} ORDER BY g.title COLLATE NOCASE LIMIT ? OFFSET ?`).bind(...args, limit, (page - 1) * limit).all();
  return c.json({ data: rows.results.map((r) => rowWithUrl(r, new URL(c.req.url).origin, 'games')), page, limit, total: count?.n || 0 });
});
app.get('/api/games/:id', async (c) => {
  const r = await c.env.DB.prepare('SELECT g.*, c.name AS console_name, c.core AS console_core FROM games g JOIN consoles c ON c.id=g.console_id WHERE g.id=? AND g.published=1').bind(c.req.param('id')).first();
  if (!r) return c.json({ error: 'بازی پیدا نشد.' }, 404);
  const images = await c.env.DB.prepare('SELECT * FROM game_images WHERE game_id=? ORDER BY sort_order,created_at').bind(c.req.param('id')).all();
  const origin = new URL(c.req.url).origin;
  return c.json({ data: { ...rowWithUrl(r, origin, 'games'), images: images.results.map((image) => imageWithUrl(image, origin)) } });
});
app.get('/api/games/:id/images', async (c) => {
  const game = await c.env.DB.prepare('SELECT id FROM games WHERE id=? AND published=1').bind(c.req.param('id')).first();
  if (!game) return c.json({ error: 'بازی پیدا نشد.' }, 404);
  const rows = await c.env.DB.prepare('SELECT * FROM game_images WHERE game_id=? ORDER BY sort_order,created_at').bind(c.req.param('id')).all();
  return c.json({ data: rows.results.map((row) => imageWithUrl(row, new URL(c.req.url).origin)) });
});
app.get('/api/bios', async (c) => {
  const consoleId = c.req.query('console_id');
  const sql = 'SELECT b.*, c.name AS console_name FROM bios_files b JOIN consoles c ON c.id=b.console_id WHERE b.published=1' + (consoleId ? ' AND b.console_id=?' : '') + ' ORDER BY b.name COLLATE NOCASE';
  const st = c.env.DB.prepare(sql);
  const rows = await (consoleId ? st.bind(consoleId) : st).all();
  return c.json({ data: rows.results.map((r) => rowWithUrl(r, new URL(c.req.url).origin, 'bios')) });
});
app.get('/api/cheats/:gameId', async (c) => {
  const rows = await c.env.DB.prepare('SELECT id,game_id,name,code,description FROM cheats WHERE game_id=? AND published=1 ORDER BY name COLLATE NOCASE').bind(c.req.param('gameId')).all();
  return c.json({ data: rows.results });
});
const MEDIA_LIMIT = 1000000;
function mediaReady(env: Env): boolean { return Boolean(env.MEDIA_DB); }
function usersReady(env: Env): boolean { return Boolean(env.SUPABASE_URL && env.SUPABASE_SECRET_KEY); }
async function mediaResponse(c: any, key: string) {
  if (!mediaReady(c.env)) return c.json({error:'فضای تصاویر Cloudflare آماده نیست.'},503);
  const media = await c.env.MEDIA_DB.prepare('SELECT content_type,content FROM media_objects WHERE id=?').bind(key).first();
  if (!media) return c.json({error:'تصویر پیدا نشد.'},404);
  const bytes = media.content instanceof ArrayBuffer ? new Uint8Array(media.content) : Uint8Array.from(media.content as number[]);
  return new Response(bytes, {status:200,headers:{'Content-Type':media.content_type,'Content-Length':String(bytes.byteLength),'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'}});
}
async function serveImage(c: any, kind: 'cover' | 'gallery', admin: boolean) {
  const id = c.req.param('id');
  const sql = kind === 'cover'
    ? `SELECT cover_key AS storage_key, published FROM games WHERE id=?`
    : `SELECT i.storage_key,g.published FROM game_images i JOIN games g ON g.id=i.game_id WHERE i.id=?`;
  const row = await c.env.DB.prepare(sql).bind(id).first();
  if (!row || (!admin && !row.published) || !row.storage_key) return c.json({ error: 'تصویر پیدا نشد.' }, 404);
  return mediaResponse(c,row.storage_key);
}
app.on(['GET','HEAD'], '/api/files/:type/:id', async (c) => {
  const type=c.req.param('type');const table=type==='games'?'games':type==='bios'?'bios_files':'';
  if (!table) return c.json({error:'نوع فایل نامعتبر است.'},404);
  const item=await c.env.DB.prepare(`SELECT storage_key,file_name,file_provider,published FROM ${table} WHERE id=?`).bind(c.req.param('id')).first<{storage_key:string;file_name:string;file_provider:string;published:number}>();
  if (!item || !item.published || item.file_provider!=='cloudflare' || !item.storage_key) return c.json({error:'فایل پیدا نشد.'},404);
  if (!mediaReady(c.env)) return c.json({error:'فضای فایل Cloudflare آماده نیست.'},503);
  const object=await c.env.MEDIA_DB!.prepare('SELECT content,size_bytes FROM media_objects WHERE id=?').bind(item.storage_key).first<{content:number[];size_bytes:number}>();
  if (!object) return c.json({error:'فایل پیدا نشد.'},404);
  const bytes=object.content instanceof ArrayBuffer ? new Uint8Array(object.content) : Uint8Array.from(object.content);
  const size=bytes.byteLength;let start=0,end=size-1,status=200;
  const range=c.req.header('Range');
  if (range) {
    const m=/^bytes=(\d*)-(\d*)$/.exec(range.trim());
    if (!m || (!m[1]&&!m[2])) return new Response(null,{status:416,headers:{'Content-Range':`bytes */${size}`,'Accept-Ranges':'bytes'}});
    if (!m[1]) {const suffix=Number(m[2]);if (!Number.isSafeInteger(suffix)||suffix<1) return new Response(null,{status:416,headers:{'Content-Range':`bytes */${size}`}});start=Math.max(0,size-suffix)}
    else {start=Number(m[1]);if (m[2]) end=Math.min(size-1,Number(m[2]));}
    if (!Number.isSafeInteger(start)||!Number.isSafeInteger(end)||start>=size||end<start) return new Response(null,{status:416,headers:{'Content-Range':`bytes */${size}`,'Accept-Ranges':'bytes'}});
    status=206;
  }
  const name=(item.file_name||'download.bin').replace(/[^A-Za-z0-9._-]/g,'_').slice(-120) || 'download.bin';
  const headers:Record<string,string>={'Content-Type':'application/octet-stream','Content-Disposition':`attachment; filename="${name}"`,'Content-Length':String(end-start+1),'Accept-Ranges':'bytes','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'};
  if (status===206) headers['Content-Range']=`bytes ${start}-${end}/${size}`;
  return new Response(c.req.method==='HEAD'?null:bytes.slice(start,end+1),{status,headers});
});
async function serveIcon(c: any, admin: boolean) {
  const type = c.req.param('type');
  if (type !== 'games' && type !== 'consoles') return c.json({error:'نوع آیکون نامعتبر است.'},404);
  const table = type === 'games' ? 'games' : 'consoles';
  const row: {icon_key:string;published?:number} | null = await c.env.DB.prepare(`SELECT icon_key${type === 'games' ? ',published' : ''} FROM ${table} WHERE id=?`).bind(c.req.param('id')).first();
  if (!row || !row.icon_key || (type==='games' && !admin && !row.published)) return c.json({error:'آیکون پیدا نشد.'},404);
  return mediaResponse(c,row.icon_key);
}
app.get('/api/icons/:type/:id', (c) => serveIcon(c,false));
app.get('/api/admin/icons/:type/:id', (c) => serveIcon(c,true));
app.get('/api/covers/:id', (c) => serveImage(c, 'cover', false));
app.get('/api/images/:id', (c) => serveImage(c, 'gallery', false));
app.get('/api/admin/covers/:id', (c) => serveImage(c, 'cover', true));
app.get('/api/admin/images/:id', (c) => serveImage(c, 'gallery', true));

const shortText = z.string().trim().min(1).max(160);
const optionalText = (max: number) => z.string().trim().max(max).default('');
const numberFlag = z.number().int().min(0).max(1).default(1);
const checksum = z.string().trim().regex(/^([a-fA-F0-9]{64})?$/, 'SHA-256 باید ۶۴ رقم هگز باشد.').default('');
const urlText = optionalText(2048).refine((value) => {
  if (!value) return true;
  try { return ['http:', 'https:'].includes(new URL(value).protocol); } catch { return false; }
}, 'آدرس باید HTTP یا HTTPS باشد.');
const fileSourceFields = {
  source: z.enum(['external','cloudflare']).default('external'),
  external_url: urlText,
  storage_key: optionalText(512),
  file_name: optionalText(255),
  size_bytes: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER).default(0),
  sha256: checksum,
  published: numberFlag,
};
const iconFields = { icon_url: urlText, icon_key: optionalText(512).refine((v) => !v || (v.startsWith('icons/') && !v.split('/').includes('..')), 'کلید آیکون نامعتبر است.') };
const consoleSchema = z.object({
  ...iconFields, name: shortText, slug: z.string().trim().min(1).max(50).regex(/^[a-z0-9]+(?:-[a-z0-9]+)*$/),
  manufacturer: optionalText(120), core: z.string().trim().min(1).max(120).regex(/^[a-zA-Z0-9_-]+$/), extensions: optionalText(500),
});
const gameSchema = z.object({
  ...iconFields, title: shortText, console_id: shortText, description: optionalText(6000), cover_url: urlText, cover_key: optionalText(512).refine((v) => !v || (v.startsWith('covers/') && !v.split('/').includes('..')), 'کلید کاور نامعتبر است.'), ...fileSourceFields,
}).superRefine((v,ctx) => { checkSource(v,ctx); if (v.source==='cloudflare' && !v.storage_key.startsWith('games/')) ctx.addIssue({code:z.ZodIssueCode.custom,message:'فایل بازی آپلودشده معتبر نیست.'}); });
const biosSchema = z.object({
  name: shortText, console_id: shortText, description: optionalText(6000), system_path: optionalText(400), ...fileSourceFields,
}).superRefine((v, ctx) => {
  checkSource(v, ctx);
  if (v.source==='cloudflare' && !v.storage_key.startsWith('bios/')) ctx.addIssue({code:z.ZodIssueCode.custom,message:'فایل BIOS آپلودشده معتبر نیست.'});
  if (v.system_path && (v.system_path.startsWith('/') || v.system_path.startsWith('\\') || v.system_path.split(/[\\/]/).some((part) => part === '..') || /^[A-Za-z]:/.test(v.system_path))) {
    ctx.addIssue({ code: z.ZodIssueCode.custom, message: 'مسیر BIOS باید نسبی و بدون .. باشد.' });
  }
});
const cheatSchema = z.object({ game_id: shortText, name: shortText, code: z.string().trim().min(1).max(10000), description: optionalText(4000), published: numberFlag });
function checkSource(v: {source: string; external_url: string; storage_key: string}, ctx: z.RefinementCtx) {
  if (v.source === 'external' && !v.external_url) ctx.addIssue({ code: z.ZodIssueCode.custom, message: 'برای منبع خارجی، آدرس دانلود لازم است.' });
  if (v.source === 'cloudflare' && !v.storage_key) ctx.addIssue({ code: z.ZodIssueCode.custom, message: 'ابتدا فایل را در Cloudflare آپلود کنید.' });
  if (v.storage_key && (v.storage_key.startsWith('/') || v.storage_key.split('/').includes('..'))) ctx.addIssue({ code: z.ZodIssueCode.custom, message: 'کلید فضای ذخیره‌سازی نامعتبر است.' });
}
const resources = {
  consoles: { table: 'consoles', schema: consoleSchema, fields: ['name','slug','manufacturer','core','extensions','icon_url','icon_key'], order: 'name' },
  games: { table: 'games', schema: gameSchema, fields: ['title','console_id','description','cover_url','cover_key','icon_url','icon_key','source','file_provider','external_url','storage_key','file_name','size_bytes','sha256','published'], order: 'title' },
  bios: { table: 'bios_files', schema: biosSchema, fields: ['name','console_id','description','system_path','source','file_provider','external_url','storage_key','file_name','size_bytes','sha256','published'], order: 'name' },
  cheats: { table: 'cheats', schema: cheatSchema, fields: ['game_id','name','code','description','published'], order: 'name' },
} as const;
type ResourceKey = keyof typeof resources;
function resource(value: string): (typeof resources)[ResourceKey] | undefined {
  return Object.prototype.hasOwnProperty.call(resources, value) ? resources[value as ResourceKey] : undefined;
}
function kindForProvider(kind:string):boolean { return kind==='games'||kind==='bios'; }
async function readJson(c: any) {
  try { return await c.req.json(); } catch { return null; }
}

app.get('/api/admin/status', (c) => c.json({
  data: { database: true, storage_provider: 'cloudflare_d1', storage_bound: mediaReady(c.env), upload_ready: mediaReady(c.env), max_upload_bytes: MEDIA_LIMIT, users_provider: 'supabase_auth', users_ready: usersReady(c.env) },
}));
app.get('/api/admin/stats', async (c) => {
  const row = await c.env.DB.prepare('SELECT (SELECT COUNT(*) FROM consoles) AS consoles, (SELECT COUNT(*) FROM games) AS games, (SELECT COUNT(*) FROM bios_files) AS bios, (SELECT COUNT(*) FROM cheats) AS cheats').first();
  return c.json({ data: row });
});
app.post('/api/admin/uploads/presign', (c) => c.json({error:'آپلود در Supabase غیرفعال است؛ فایل بازی را با لینک خارجی ثبت کنید.'},410));
function imageType(bytes: Uint8Array): string | null {
  if (bytes.length >= 8 && [137,80,78,71,13,10,26,10].every((n,i) => bytes[i] === n)) return 'image/png';
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return 'image/jpeg';
  if (bytes.length >= 12 && String.fromCharCode(...bytes.slice(0,4)) === 'RIFF' && String.fromCharCode(...bytes.slice(8,12)) === 'WEBP') return 'image/webp';
  if (bytes.length >= 6 && ['GIF87a','GIF89a'].includes(String.fromCharCode(...bytes.slice(0,6)))) return 'image/gif';
  return null;
}
async function readLimitedBody(request: Request): Promise<Uint8Array | null> {
  const declared = Number(request.headers.get('Content-Length') || '0');
  if (declared > MEDIA_LIMIT || !request.body) return null;
  const reader = request.body.getReader(); let total = 0; const chunks: Uint8Array[] = [];
  while (true) {
    const {value,done} = await reader.read(); if (done) break;
    total += value.byteLength; if (total > MEDIA_LIMIT) { await reader.cancel(); return null; }
    chunks.push(value);
  }
  const bytes = new Uint8Array(total); let offset = 0; for (const chunk of chunks) { bytes.set(chunk,offset); offset += chunk.byteLength; }
  return bytes;
}
app.post('/api/admin/uploads/image', async (c) => {
  if (!mediaReady(c.env)) return c.json({error:'فضای تصاویر Cloudflare آماده نیست.'},503);
  const category = c.req.header('X-Image-Category');
  if (category !== 'covers' && category !== 'screenshots' && category !== 'icons') return c.json({error:'نوع تصویر نامعتبر است.'},400);
  const bytes = await readLimitedBody(c.req.raw);
  if (!bytes) return c.json({error:'حجم تصویر حداکثر ۱ مگابایت است.'},413);
  const detected = imageType(bytes);
  if (!detected || detected !== (c.req.header('Content-Type') || '').toLowerCase().split(';')[0]) return c.json({error:'فقط تصاویر معتبر PNG، JPEG، WebP یا GIF مجاز است.'},400);
  const key = `${category}/${crypto.randomUUID()}`;
  await c.env.MEDIA_DB!.prepare('INSERT INTO media_objects (id,content_type,size_bytes,content) VALUES (?,?,?,?)').bind(key,detected,bytes.byteLength,bytes).run();
  return c.json({data:{key,size_bytes:bytes.byteLength,content_type:detected}},201);
});
app.post('/api/admin/uploads/file', async (c) => {
  if (!mediaReady(c.env)) return c.json({error:'فضای فایل Cloudflare آماده نیست.'},503);
  const category=c.req.header('X-File-Category');
  if (category!=='games' && category!=='bios') return c.json({error:'نوع فایل نامعتبر است.'},400);
  if ((c.req.header('Content-Type')||'').toLowerCase().split(';')[0] !== 'application/octet-stream') return c.json({error:'نوع درخواست فایل نامعتبر است.'},415);
  const bytes=await readLimitedBody(c.req.raw);
  if (!bytes) return c.json({error:'حجم فایل حداکثر ۱,۰۰۰,۰۰۰ بایت است؛ فایل بزرگ‌تر را با لینک خارجی ثبت کنید.'},413);
  if (!bytes.byteLength) return c.json({error:'فایل خالی قابل آپلود نیست.'},400);
  const key=`${category}/${crypto.randomUUID()}`;
  await c.env.MEDIA_DB!.prepare('INSERT INTO media_objects (id,content_type,size_bytes,content) VALUES (?,?,?,?)').bind(key,'application/octet-stream',bytes.byteLength,bytes).run();
  return c.json({data:{key,size_bytes:bytes.byteLength,content_type:'application/octet-stream'}},201);
});
app.get('/api/admin/media', async (c) => {
  if (!mediaReady(c.env)) return c.json({error:'فضای تصاویر Cloudflare آماده نیست.'},503);
  const rows = await c.env.MEDIA_DB!.prepare('SELECT id,content_type,size_bytes,created_at FROM media_objects ORDER BY created_at DESC LIMIT 250').all();
  // Keep the number of compound SELECT terms within D1's limit.
  const [gameRefs, otherRefs] = await Promise.all([
    c.env.DB.prepare("SELECT cover_key AS key FROM games WHERE cover_key <> '' UNION SELECT icon_key AS key FROM games WHERE icon_key <> '' UNION SELECT storage_key AS key FROM games WHERE file_provider='cloudflare' AND storage_key <> ''").all<{key:string}>(),
    c.env.DB.prepare("SELECT icon_key AS key FROM consoles WHERE icon_key <> '' UNION SELECT storage_key AS key FROM game_images WHERE storage_key <> '' UNION SELECT storage_key AS key FROM bios_files WHERE file_provider='cloudflare' AND storage_key <> ''").all<{key:string}>(),
  ]);
  const used = new Set([...gameRefs.results,...otherRefs.results].map((r) => r.key));
  return c.json({data:rows.results.map((row:any) => ({...row,used:used.has(row.id),url:`/api/admin/media/preview?key=${encodeURIComponent(row.id)}`}))});
});
app.get('/api/admin/media/preview', async (c) => mediaResponse(c,c.req.query('key') || ''));
app.post('/api/admin/media/delete', async (c) => {
  const parsed = z.object({key:z.string().min(1).max(512)}).safeParse(await readJson(c));
  if (!parsed.success || !mediaReady(c.env)) return c.json({error:'تصویر نامعتبر است.'},400);
  const key = parsed.data.key;
  const ref = await c.env.DB.prepare("SELECT 1 AS used FROM games WHERE cover_key=? OR icon_key=? OR (file_provider='cloudflare' AND storage_key=?) UNION ALL SELECT 1 FROM consoles WHERE icon_key=? UNION ALL SELECT 1 FROM game_images WHERE storage_key=? UNION ALL SELECT 1 FROM bios_files WHERE file_provider='cloudflare' AND storage_key=? LIMIT 1").bind(key,key,key,key,key,key).first();
  if (ref) return c.json({error:'این تصویر هنوز در یک بازی استفاده می‌شود.'},409);
  const result = await c.env.MEDIA_DB!.prepare('DELETE FROM media_objects WHERE id=?').bind(key).run();
  return result.meta.changes ? c.json({ok:true}) : c.json({error:'تصویر پیدا نشد.'},404);
});
app.get('/api/admin/users', async (c) => {
  if (!usersReady(c.env)) return c.json({error:'اتصال کاربران Supabase آماده نیست.'},503);
  const page = Math.max(1,Math.min(10000,Number.parseInt(c.req.query('page') || '1',10) || 1));
  const url = new URL('/auth/v1/admin/users',c.env.SUPABASE_URL!);url.searchParams.set('page',String(page));url.searchParams.set('per_page','100');
  const key=c.env.SUPABASE_SECRET_KEY!;
  const headers:Record<string,string>={apikey:key};if (!key.startsWith('sb_secret_')) headers.Authorization='Bearer '+key;
  const response=await fetch(url,{headers});
  if (!response.ok) return c.json({error:'دریافت کاربران از Supabase ممکن نشد.'},502);
  const data=await response.json<{users?:any[]}>();
  return c.json({data:(data.users || []).map((u) => ({id:u.id,email:u.email || '',created_at:u.created_at,last_sign_in_at:u.last_sign_in_at,email_confirmed_at:u.email_confirmed_at,banned_until:u.banned_until}))});
});

const gallerySchema = z.object({
  storage_key: optionalText(512).refine((v) => !v || (v.startsWith('screenshots/') && !v.split('/').includes('..')), 'کلید تصویر نامعتبر است.'),
  image_url: urlText,
  alt_text: optionalText(240),
  sort_order: z.number().int().min(0).max(10000).default(0),
}).refine((v) => Boolean(v.storage_key) !== Boolean(v.image_url), 'یک فایل یا یک آدرس تصویر انتخاب کنید.');
app.get('/api/admin/games/:id/images', async (c) => {
  const game = await c.env.DB.prepare('SELECT id FROM games WHERE id=?').bind(c.req.param('id')).first();
  if (!game) return c.json({error:'بازی پیدا نشد.'},404);
  const rows = await c.env.DB.prepare('SELECT * FROM game_images WHERE game_id=? ORDER BY sort_order,created_at').bind(c.req.param('id')).all();
  return c.json({data:rows.results.map((row) => imageWithUrl(row, new URL(c.req.url).origin, true))});
});
app.post('/api/admin/games/:id/images', async (c) => {
  const parsed = gallerySchema.safeParse(await readJson(c));
  if (!parsed.success) return c.json({error:parsed.error.issues.map((i) => i.message).join('؛ ')},400);
  const game = await c.env.DB.prepare('SELECT id FROM games WHERE id=?').bind(c.req.param('id')).first();
  if (!game) return c.json({error:'بازی پیدا نشد.'},404);
  if (parsed.data.storage_key && (!mediaReady(c.env) || !await c.env.MEDIA_DB!.prepare('SELECT id FROM media_objects WHERE id=?').bind(parsed.data.storage_key).first())) return c.json({error:'تصویر آپلودشده پیدا نشد.'},400);
  const count = await c.env.DB.prepare('SELECT COUNT(*) AS n FROM game_images WHERE game_id=?').bind(c.req.param('id')).first<{n:number}>();
  if ((count?.n || 0) >= 20) return c.json({error:'حداکثر ۲۰ تصویر برای هر بازی مجاز است.'},400);
  const id = crypto.randomUUID();
  await c.env.DB.prepare('INSERT INTO game_images (id,game_id,storage_key,image_url,alt_text,sort_order) VALUES (?,?,?,?,?,?)').bind(id,c.req.param('id'),parsed.data.storage_key,parsed.data.image_url,parsed.data.alt_text,parsed.data.sort_order).run();
  const row = await c.env.DB.prepare('SELECT * FROM game_images WHERE id=?').bind(id).first();
  return c.json({data:imageWithUrl(row,new URL(c.req.url).origin,true)},201);
});
app.delete('/api/admin/games/:id/images/:imageId', async (c) => {
  const r = await c.env.DB.prepare('DELETE FROM game_images WHERE id=? AND game_id=?').bind(c.req.param('imageId'),c.req.param('id')).run();
  return r.meta.changes ? c.json({ok:true}) : c.json({error:'تصویر پیدا نشد.'},404);
});

app.get('/api/admin/:kind', async (c) => {
  const def = resource(c.req.param('kind'));
  if (!def) return c.json({ error: 'بخش موردنظر پیدا نشد.' }, 404);
  const rows = await c.env.DB.prepare(`SELECT * FROM ${def.table} ORDER BY ${def.order} COLLATE NOCASE`).all();
  const kind=c.req.param('kind');
  return c.json({ data: rows.results.map((row:any) => kind==='games'||kind==='bios' ? {...row,source:row.file_provider || row.source} : row) });
});
app.post('/api/admin/:kind', async (c) => {
  const kind = c.req.param('kind'), def = resource(kind);
  if (!def) return c.json({ error: 'بخش موردنظر پیدا نشد.' }, 404);
  const parsed = def.schema.safeParse(await readJson(c));
  if (!parsed.success) return c.json({ error: parsed.error.issues.map((i) => i.message).join('؛ ') }, 400);
  const data = parsed.data as Record<string, any>;
  if (data.cover_key && (!mediaReady(c.env) || !await c.env.MEDIA_DB!.prepare('SELECT id FROM media_objects WHERE id=?').bind(data.cover_key).first())) return c.json({ error: 'تصویر جلد آپلودشده پیدا نشد.' }, 400);
  if (data.icon_key && (!mediaReady(c.env) || !await c.env.MEDIA_DB!.prepare('SELECT id FROM media_objects WHERE id=?').bind(data.icon_key).first())) return c.json({ error: 'آیکون آپلودشده پیدا نشد.' }, 400);
  if (kindForProvider(c.req.param('kind'))) {
    data.file_provider=data.source;
    if (data.file_provider==='cloudflare' && (!mediaReady(c.env) || !await c.env.MEDIA_DB!.prepare('SELECT id FROM media_objects WHERE id=?').bind(data.storage_key).first())) return c.json({error:'فایل آپلودشده پیدا نشد.'},400);
    data.source='external';
  }
  const id = kind === 'consoles' ? data.slug : crypto.randomUUID();
  try {
    const fields = [...def.fields];
    await c.env.DB.prepare(`INSERT INTO ${def.table} (id,${fields.join(',')}) VALUES (${Array(fields.length+1).fill('?').join(',')})`).bind(id, ...fields.map((f) => data[f])).run();
    const row = await c.env.DB.prepare(`SELECT * FROM ${def.table} WHERE id=?`).bind(id).first();
    return c.json({ data: kindForProvider(c.req.param('kind')) && row ? {...row,source:(row as any).file_provider} : row }, 201);
  } catch (e) {
    return c.json({ error: 'ثبت انجام نشد؛ شناسه تکراری است یا مورد وابسته وجود ندارد.' }, 409);
  }
});
app.put('/api/admin/:kind/:id', async (c) => {
  const def = resource(c.req.param('kind'));
  if (!def) return c.json({ error: 'بخش موردنظر پیدا نشد.' }, 404);
  const id = c.req.param('id');
  const parsed = def.schema.safeParse(await readJson(c));
  if (!parsed.success) return c.json({ error: parsed.error.issues.map((i) => i.message).join('؛ ') }, 400);
  const data = parsed.data as Record<string, any>;
  if (data.cover_key && (!mediaReady(c.env) || !await c.env.MEDIA_DB!.prepare('SELECT id FROM media_objects WHERE id=?').bind(data.cover_key).first())) return c.json({ error: 'تصویر جلد آپلودشده پیدا نشد.' }, 400);
  if (data.icon_key && (!mediaReady(c.env) || !await c.env.MEDIA_DB!.prepare('SELECT id FROM media_objects WHERE id=?').bind(data.icon_key).first())) return c.json({ error: 'آیکون آپلودشده پیدا نشد.' }, 400);
  if (kindForProvider(c.req.param('kind'))) {
    data.file_provider=data.source;
    if (data.file_provider==='cloudflare' && (!mediaReady(c.env) || !await c.env.MEDIA_DB!.prepare('SELECT id FROM media_objects WHERE id=?').bind(data.storage_key).first())) return c.json({error:'فایل آپلودشده پیدا نشد.'},400);
    data.source='external';
  }
  const exists = await c.env.DB.prepare(`SELECT id FROM ${def.table} WHERE id=?`).bind(id).first();
  if (!exists) return c.json({ error: 'مورد پیدا نشد.' }, 404);
  try {
    const fields = [...def.fields];
    await c.env.DB.prepare(`UPDATE ${def.table} SET ${fields.map((f) => `${f}=?`).join(',')}, updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?`).bind(...fields.map((f) => data[f]), id).run();
    const row = await c.env.DB.prepare(`SELECT * FROM ${def.table} WHERE id=?`).bind(id).first();
    return c.json({ data: kindForProvider(c.req.param('kind')) && row ? {...row,source:(row as any).file_provider} : row });
  } catch (e) { return c.json({ error: 'به‌روزرسانی انجام نشد؛ داده‌ها یا وابستگی‌ها را بررسی کنید.' }, 409); }
});
app.delete('/api/admin/:kind/:id', async (c) => {
  const def = resource(c.req.param('kind'));
  if (!def) return c.json({ error: 'بخش موردنظر پیدا نشد.' }, 404);
  try {
    const result = await c.env.DB.prepare(`DELETE FROM ${def.table} WHERE id=?`).bind(c.req.param('id')).run();
    if (!result.meta.changes) return c.json({ error: 'مورد پیدا نشد.' }, 404);
    return c.json({ ok: true });
  } catch (e) { return c.json({ error: 'حذف ممکن نیست؛ ابتدا موارد وابسته را حذف کنید.' }, 409); }
});
app.notFound((c) => c.json({ error: 'مسیر پیدا نشد.' }, 404));
app.onError((e, c) => { console.error('Request failed:', e.name); return c.json({ error: 'خطای داخلی سرویس.' }, 500); });
export default app;
