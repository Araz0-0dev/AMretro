// Run against local or deployed Worker using RETRO_BASE and RETRO_ADMIN_PASSWORD.
// No real content is modified: uniquely named temporary entries and their uploaded bytes are deleted afterwards.
const {chromium}=require('playwright');
const crypto=require('crypto');
const assert=require('assert').strict;
const base=process.env.RETRO_BASE||'http://127.0.0.1:8787';
const password=process.env.RETRO_ADMIN_PASSWORD||'local-preview-only';
const image=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jR9sAAAAASUVORK5CYII=','base64');
const binary=crypto.randomBytes(600*1024),sha=crypto.createHash('sha256').update(binary).digest('hex');
(async()=>{
 let gameId,biosId;const keys=[];const errors=[];
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});const ctx=await browser.newContext({httpCredentials:{username:'admin',password},viewport:{width:1280,height:800}});const p=await ctx.newPage();
 p.on('pageerror',e=>errors.push(e.message));
 p.on('response',async r=>{if(r.request().method()==='POST'&&/\/api\/admin\/uploads\/(image|file)$/.test(r.url())&&r.ok()){try{keys.push((await r.json()).data.key)}catch{}}});
 p.on('dialog',d=>d.accept());
 try{
  await p.goto(base+'/admin',{waitUntil:'networkidle'});await p.getByText('سه قدم تا اضافه کردن بازی').waitFor();
  await p.locator('#nav [data-nav=help]').click();await p.getByText('قدم‌به‌قدم با هم پیش می‌رویم').waitFor();await p.getByText('scph5501.bin',{exact:true}).first().waitFor();
  await p.getByRole('button',{name:'افزودن بازی'}).first().click();await p.locator('#f-title').fill('Temporary beginner test');await p.locator('#f-console_id').selectOption('nes');
  await p.locator('#save-button').click();await p.getByText('اول یک فایل انتخاب کن یا گزینهٔ لینک را بزن.').waitFor();
  await p.locator('[data-source=external]').click();assert.equal(await p.locator('#source-upload').isVisible(),false);await p.locator('#f-external_url').fill('https://example.com/direct-game.zip');await p.locator('#f-external_url').blur();assert.equal(await p.locator('#f-file_name').inputValue(),'direct-game.zip');
  await p.locator('details.optional summary').filter({hasText:'عکس‌ها و توضیحات'}).click();
  await p.locator('#upload-cover').setInputFiles({name:'cover.png',mimeType:'image/png',buffer:image});await p.waitForFunction(()=>!!document.querySelector('#f-cover_key')?.value);
  await p.locator('#upload-icon').setInputFiles({name:'icon.png',mimeType:'image/png',buffer:image});await p.waitForFunction(()=>!!document.querySelector('#f-icon_key')?.value);
  await p.screenshot({path:'/tmp/retro-admin-simple-desktop.png'});
  let create=p.waitForResponse(r=>r.url().endsWith('/api/admin/games')&&r.request().method()==='POST');await p.locator('#save-button').click();let response=await create;assert.equal(response.status(),201,await response.text());gameId=(await response.json()).data.id;
  let game=(await (await p.request.get(base+'/api/games/'+gameId)).json()).data;assert.equal(game.source,'external');assert.equal(game.file_name,'direct-game.zip');assert.deepEqual(await (await p.request.get(game.cover_url)).body(),image);assert.deepEqual(await (await p.request.get(game.icon_url)).body(),image);
  await p.locator('#nav [data-nav=games]').click();await p.locator(`button[data-edit="games"][data-id="${gameId}"]`).click();await p.locator('details.optional summary').filter({hasText:'عکس‌ها و توضیحات'}).click();
  let gal=p.waitForResponse(r=>r.url().includes('/images')&&r.request().method()==='POST');await p.locator('#upload-gallery').setInputFiles({name:'gallery.png',mimeType:'image/png',buffer:image});assert.equal((await gal).status(),201);await p.waitForSelector('#gallery-grid img');
  game=(await (await p.request.get(base+'/api/games/'+gameId)).json()).data;assert.equal(game.images.length,1);assert.deepEqual(await (await p.request.get(game.images[0].url)).body(),image);await p.locator('#cancel-button').click();
  await p.locator('#nav [data-nav=bios]').click();await p.locator('#add-button').click();await p.locator('#f-name').fill('Temporary BIOS beginner');await p.locator('#f-console_id').selectOption('ps1');await p.locator('#f-system_path').fill('scph5501.bin');
  assert.equal(await p.locator('#source-upload').isVisible(),true);assert.equal(await p.locator('#source-external').isVisible(),false);await p.locator('#upload-file').setInputFiles({name:'scph5501.bin',mimeType:'application/octet-stream',buffer:binary});await p.waitForFunction(()=>!!document.querySelector('#f-storage_key')?.value,null,{timeout:60000});assert.equal(await p.locator('#f-sha256').inputValue(),sha);
  create=p.waitForResponse(r=>r.url().endsWith('/api/admin/bios')&&r.request().method()==='POST');await p.locator('#save-button').click();response=await create;assert.equal(response.status(),201,await response.text());biosId=(await response.json()).data.id;
  let bios=(await (await p.request.get(base+'/api/bios')).json()).data.find(x=>x.id===biosId);assert.equal(bios.source,'cloudflare');assert.equal(bios.sha256,sha);assert.deepEqual(await (await p.request.get(bios.download_url)).body(),binary);
  let partial=await p.request.get(bios.download_url,{headers:{Range:'bytes=100-999'}});assert.equal(partial.status(),206);assert.deepEqual(await partial.body(),binary.subarray(100,1000));
  for(const key of keys){let used=await p.request.post(base+'/api/admin/media/delete',{data:{key}});assert.equal(used.status(),409,`used media ${key} should be protected`)}
  await p.setViewportSize({width:390,height:844});await p.locator('#nav [data-nav=overview]').click();await p.screenshot({path:'/tmp/retro-admin-simple-mobile.png'});assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'mobile overflow');await p.getByRole('button',{name:'افزودن بازی'}).first().click();assert.equal(await p.locator('#modal-wrap').isVisible(),true);assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'modal overflow');
  assert.deepEqual(errors,[]);console.log('PASS: guide, simple choices, validation, external game, cover/icon/gallery, 600-KiB BIOS, range, protection, mobile.');
 }finally{
  if(gameId)await p.request.delete(base+'/api/admin/games/'+gameId,{data:{}});
  if(biosId)await p.request.delete(base+'/api/admin/bios/'+biosId,{data:{}});
  // uploaded keys are protected while attached; game deletion cascades its gallery
  for(const key of keys){const r=await p.request.post(base+'/api/admin/media/delete',{data:{key}});if(![200,404].includes(r.status()))console.error('Cleanup status for test upload',r.status());}
  await browser.close();
 }
})().catch(e=>{console.error(e);process.exit(1)});
