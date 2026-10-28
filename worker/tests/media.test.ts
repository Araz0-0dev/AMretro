import test from 'node:test';
import assert from 'node:assert/strict';
import app from '../src/index';
const blob = new Uint8Array([137,80,78,71,13,10,26,10,1,2,3]);
const auth='Basic '+btoa('admin:test-password');
const media=new Map<string,{content_type:string;size_bytes:number;content:number[]}>();
let publicKey='';
const catalog:any={prepare:(sql:string)=>({bind:(...args:any[])=>({
  first:async()=>{
    if(sql.includes('SELECT cover_key')) return args[0]==='published'?{storage_key:publicKey,published:1}:args[0]==='draft'?{storage_key:publicKey,published:0}:null;
    if(sql.includes('SELECT 1 AS used'))return args[0]===publicKey?{used:1}:null;
    return null;
  },
  all:async()=>({results:[]}),run:async()=>({meta:{changes:0}})
})})};
const mediaDb:any={prepare:(sql:string)=>({
  bind:(...args:any[])=>({
    first:async()=>media.get(args[0])||null,
    run:async()=>{if(sql.startsWith('INSERT')){media.set(args[0],{content_type:args[1],size_bytes:args[2],content:Array.from(args[3])});return {meta:{changes:1}}}if(sql.startsWith('DELETE')){const ok=media.delete(args[0]);return {meta:{changes:ok?1:0}}}return {meta:{changes:0}}}
  }),all:async()=>({results:[]})
})};
const env:any={DB:catalog,MEDIA_DB:mediaDb,ADMIN_USER:'admin',ADMIN_PASSWORD:'test-password',SUPABASE_URL:'https://example.supabase.co',SUPABASE_SECRET_KEY:'legacy-server-secret'};
const url=(p:string)=>'https://panel.example'+p;
const uploadHeaders={Authorization:auth,Origin:'https://panel.example','Content-Type':'image/png','X-Retro-Upload':'1','X-Image-Category':'covers'};
await test('Cloudflare image uploader and Supabase user isolation',async(t)=>{
 const original=globalThis.fetch;
 globalThis.fetch=(async(input:any,init?:RequestInit)=>{
  assert.equal(String(input).startsWith('https://example.supabase.co/auth/v1/admin/users?'),true);
  assert.equal((init?.headers as any).apikey,'legacy-server-secret');
  return Response.json({users:[{id:'id-1',email:'a@example.com',created_at:'2026-01-01',encrypted_password:'never-return',app_metadata:{secret:'never-return'}}]});
 }) as typeof fetch;
 try{
  await t.test('no admin access without Basic Auth',async()=>{const r=await app.request(url('/api/admin/users'),{},env);assert.equal(r.status,401)});
  await t.test('rejects cross-site or missing origin uploads',async()=>{const r=await app.request(url('/api/admin/uploads/image'),{method:'POST',headers:{...uploadHeaders,Origin:'https://attacker.example'},body:blob},env);assert.equal(r.status,403)});
  await t.test('rejects unrecognized image bytes',async()=>{const r=await app.request(url('/api/admin/uploads/image'),{method:'POST',headers:uploadHeaders,body:new Uint8Array([1,2,3])},env);assert.equal(r.status,400)});
  await t.test('rejects images larger than a million bytes',async()=>{const r=await app.request(url('/api/admin/uploads/image'),{method:'POST',headers:uploadHeaders,body:new Uint8Array(1000001)},env);assert.equal(r.status,413)});
  await t.test('stores image in Cloudflare D1',async()=>{const r=await app.request(url('/api/admin/uploads/image'),{method:'POST',headers:uploadHeaders,body:blob},env);assert.equal(r.status,201);const d:any=await r.json();publicKey=d.data.key;assert.ok(publicKey.startsWith('covers/'));assert.deepEqual(media.get(publicKey)?.content,Array.from(blob))});
  await t.test('publishes linked images, not unpublished drafts',async()=>{let r=await app.request(url('/api/covers/published'),{},env);assert.equal(r.status,200);assert.equal(r.headers.get('content-type'),'image/png');assert.deepEqual(new Uint8Array(await r.arrayBuffer()),blob);r=await app.request(url('/api/covers/draft'),{},env);assert.equal(r.status,404)});
  await t.test('refuses to remove an image used by a game',async()=>{const r=await app.request(url('/api/admin/media/delete'),{method:'POST',headers:{Authorization:auth,Origin:'https://panel.example','Content-Type':'application/json'},body:JSON.stringify({key:publicKey})},env);assert.equal(r.status,409)});
  await t.test('exposes only safe user fields to admin',async()=>{const r=await app.request(url('/api/admin/users'),{headers:{Authorization:auth}},env);assert.equal(r.status,200);const text=await r.text();assert.match(text,/a@example.com/);assert.ok(!text.includes('never-return'));assert.ok(!text.includes('legacy-server-secret'))});
  await t.test('old Supabase upload endpoint is disabled',async()=>{const r=await app.request(url('/api/admin/uploads/presign'),{method:'POST',headers:{Authorization:auth,Origin:'https://panel.example','Content-Type':'application/json'},body:'{}'},env);assert.equal(r.status,410)});
 }finally{globalThis.fetch=original}
});
