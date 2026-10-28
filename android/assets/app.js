// Android 7 devices can ship WebView versions older than Element.append/replaceWith.
if (!Element.prototype.append) Element.prototype.append = function() {
  for (var i=0;i<arguments.length;i++) this.appendChild(arguments[i] instanceof Node ? arguments[i] : document.createTextNode(String(arguments[i])));
};
if (!NodeList.prototype.forEach) NodeList.prototype.forEach = Array.prototype.forEach;
(() => {
  'use strict';
  const state = { tab:'home', catalog:[], library:[], error:'', trophy:null, ra:null };
  const screen = document.getElementById('screen'), modal = document.getElementById('modal'), content = document.getElementById('modalContent');
  const bridge = window.Android || {};
  const $ = (tag, className, text) => { const e=document.createElement(tag); if(className)e.className=className; if(text!==undefined)e.textContent=String(text); return e; };
  const clear = node => { while(node.firstChild)node.removeChild(node.firstChild); };
  const btn = (text,cls,action) => { const b=$('button','btn '+(cls||''),text); b.type='button'; b.addEventListener('click',action);return b; };
  const call = (name,...args) => { if(typeof bridge[name]==='function')bridge[name](...args); else toast('این قابلیت داخل برنامهٔ اندروید در دسترس است.'); };
  let toastTimer;
  function toast(text){ const el=document.getElementById('toast'); el.textContent=text||'';el.classList.remove('hidden');clearTimeout(toastTimer);toastTimer=setTimeout(()=>el.classList.add('hidden'),4200); }
  function showModal(){modal.classList.remove('hidden');}
  function closeModal(){modal.classList.add('hidden');state.ra=null;state.trophy=null;}
  modal.addEventListener('click',e=>{if(e.target===modal)closeModal();});
  document.querySelectorAll('.bottom button').forEach(b=>b.addEventListener('click',()=>navigate(b.dataset.tab)));
  function navigate(tab){ state.tab=tab;document.querySelectorAll('.bottom button').forEach(b=>b.classList.toggle('active',b.dataset.tab===tab));render();window.scrollTo(0,0); }
  const section = (title,description) => { const x=$('div','section-head'),a=$('div');a.append($('h2','',title));if(description)a.append($('p','',description));x.append(a);return x; };
  const title = (t,subtitle) => { const wrap=$('div');wrap.append($('div','eyebrow','AMRETRO  /  COLLECTION'),$('h1','page-title',t),$('p','subtitle',subtitle));return wrap; };
  const panel = (t,txt) => { const e=$('div','setting');e.append($('b','',t),$('p','',txt));return e; };
  function empty(titleText,description,withImport){const e=$('div','empty');e.append($('div','empty-icon','✧'),$('h3','',titleText),$('p','',description));if(withImport)e.append(btn('＋ افزودن فایل بازی','secondary',()=>call('importGame')));return e;}
  function consoleName(id){return ({gb:'Game Boy',gbc:'Game Boy Color',gba:'Game Boy Advance',nes:'NES',snes:'SNES',ps1:'PlayStation',psp:'PSP',n64:'Nintendo 64',genesis:'Mega Drive',arcade:'Arcade'})[id]||id||'بازی کلاسیک';}
  function nativeGame(g){return ['gb','gbc','gba'].includes(g.console_id);}
  function gameCard(g,installed){
    const card=$('article','game'), imageUrl=g.cover_url||g.icon_url||'';
    if(/^https:\/\//i.test(imageUrl)){const img=$('img','cover');img.alt='';img.loading='lazy';img.src=imageUrl;img.addEventListener('error',()=>{if(img.parentNode)img.parentNode.replaceChild($('div','cover cover-placeholder','✦'),img);});card.append(img);}else card.append($('div','cover cover-placeholder','✦'));
    const info=$('div','game-info');info.append($('h3','',g.title||'بازی بدون نام'));
    info.append($('p','',consoleName(g.console_id)+(installed?(nativeGame(g)?'  ·  اجرای داخلی':'  ·  نیازمند RetroArch'):'')));
    const actions=$('div','game-actions');
    if(installed){
      if(nativeGame(g))actions.append(btn('▶ بازی','mini',()=>call('play',g.id)));
      actions.append(btn('تروفی‌ها','mini secondary',()=>call('trophy',g.id)));
      actions.append(btn('•••','mini outline',()=>showGameMenu(g)));
    }else actions.append(btn('نصب بازی','mini',()=>{call('install',g.id);toast('در حال آماده‌سازی نصب…');}));
    info.append(actions);card.append(info);return card;
  }
  function gameList(target,games,installed){const list=$('div','cardlist');games.forEach(g=>list.append(gameCard(g,installed)));target.append(list);}
  function home(root){
    const hero=$('section','hero'), body=$('div','hero-content');body.append($('span','eyebrow','بازی کن، دوباره کشف کن'),$('h1','','هر بازی، یک خاطره'),$('p','','کلاسیک‌های محبوبت را در یک جای دوست‌داشتنی کنار هم داشته باش.'),btn('＋ افزودن بازی','light',()=>call('importGame')));hero.append(body);root.append(hero);
    const chips=$('div','quick');['لمسی و همراه','GB / GBC / GBA داخلی','RetroArch برای بقیه','تروفی‌های بازیکن'].forEach(x=>chips.append($('span','',x)));root.append(chips);
    const h=section('کتابخانهٔ من',state.library.length?`${state.library.length} بازی آماده است`:'دنیای بازی‌هات از اینجا شروع می‌شود');h.append(btn('نمایش همه','mini secondary',()=>navigate('library')));root.append(h);
    if(state.library.length)gameList(root,state.library.slice(0,3),true);else root.append(empty('هنوز بازی نداری','یک فایل GB، GBC یا GBA انتخاب کن یا از فروشگاه نصب کن.',true));
    root.append(section('ویژگی‌های نسخهٔ اندروید','برای تجربه‌ای ساده و همراه'));const features=$('div','feature-grid');
    [['◉','دستهٔ لمسی','روی تصویر بازی'],['♛','تروفی‌ها','فعالیت و RetroAchievements']].forEach(([symbol,t,d])=>{const f=$('div','feature');f.append($('div','symbol',symbol),$('b','',t),$('small','',d));features.append(f);});root.append(features);
  }
  function store(root){
    root.append(title('فروشگاه بازی‌ها','کاتالوگ از پنل AMretro دریافت می‌شود. فقط فایل‌هایی را نصب کن که حق استفاده از آن‌ها را داری.'));
    const sync=btn('↻ همگام‌سازی','secondary',()=>call('syncCatalog'));root.append(sync);
    if(state.error)root.append($('div','notice',state.error));
    const installed=new Set(state.library.map(g=>g.id));const available=state.catalog.filter(g=>!installed.has(g.id));root.append(section('همهٔ بازی‌ها',`${available.length} بازی در دسترس`));
    if(available.length)gameList(root,available,false);else root.append(empty('فروشگاه فعلاً خالی است','بازی‌ها را از پنل مدیریت منتشر کن؛ پس از همگام‌سازی اینجا دیده می‌شوند.',false));
  }
  function library(root){
    root.append(title('کتابخانهٔ من','بازی‌های نصب‌شده، تروفی‌ها و فایل‌های شخصی‌ات در همین دستگاه هستند.'));
    root.append(btn('＋ افزودن فایل GB / GBC / GBA','',()=>call('importGame')));
    root.append(section('بازی‌های من',`${state.library.length} بازی`));
    if(state.library.length)gameList(root,state.library,true);else root.append(empty('کتابخانه خالی است','با افزودن فایل یا نصب از فروشگاه، اولین بازی‌ات را شروع کن.',false));
  }
  function settings(root){
    root.append(title('تنظیمات و راهنما','پیش از شروع بازی، این چند نکته را بدان.'));
    const a=panel('اجرای داخلی با دستهٔ لمسی','GB، Game Boy Color و Game Boy Advance با هستهٔ mGBA داخل AMretro اجرا می‌شوند. دکمه‌های دوبعدی و لمسی روی بازی قرار می‌گیرند. فایل بازی همراه برنامه نیست.');root.append(a);
    const b=panel('سایر کنسول‌ها با RetroArch','RetroArch و هستهٔ مناسب را جداگانه نصب کن. در تنظیمات RetroArch، نمایش Overlay را فعال کن و مسیر System/BIOS را روی Download/AMretro/System بگذار. برنامه‌های مختلف RetroArch و نسخه‌های اندروید ممکن است دسترسی متفاوتی به فایل‌ها داشته باشند.');b.append(btn('دریافت و راهنمای RetroArch','mini secondary',()=>call('openRetroSite')));root.append(b);
    const c=panel('دستاوردهای واقعی RetroAchievements','داخل RetroArch با حساب خودت وارد شو و Achievements را فعال کن. AMretro با Web API Key و شناسهٔ بازی، دستاوردهای واقعی را فقط نمایش می‌دهد؛ بازی داخلی mGBA آن‌ها را باز نمی‌کند.');c.append(btn('راهنمای کلید وب','mini secondary',()=>call('openRaHelp')),btn('پاک‌کردن کلید','mini outline',()=>call('clearRa')));root.append(c);
    root.append(panel('تروفی‌های محلی','چهار رتبهٔ برنز، نقره، طلا و پلاتینیوم بر پایهٔ فعالیت روی همین دستگاه هستند، نه تروفی رسمی RetroAchievements. زمان RetroArch پس از تأیید تو تقریبی ثبت می‌شود. هنوز همگام‌سازی حساب وجود ندارد.'));
    root.append(panel('BIOS و محدودیت نسخهٔ آزمایشی','پیش از نصب، BIOSهای تکی منتشرشدهٔ همان کنسول بررسی می‌شوند؛ فایل متفاوت بازنویسی نمی‌شود. BIOS فشرده، 7z و RAR فعلاً پشتیبانی نمی‌شوند. برای نصب جدید اینترنت و لینک مستقیم HTTPS لازم است.'));
  }
  function render(){clear(screen);const root=$('div','screen');screen.append(root);({home,store,library,settings}[state.tab]||home)(root);}
  function modalHeader(t){const h=$('div','sheet-head');h.append($('h2','',t),btn('×','close',closeModal));content.append(h);}
  function showGameMenu(g){
    clear(content);modalHeader(g.title);content.append($('p','muted',nativeGame(g)?'برای بازی داخلی از دکمهٔ بازی استفاده کن. RetroArch برای دریافت دستاوردهای رسمی جداگانه لازم است.':'این کنسول با RetroArch اجرا می‌شود.'));
    content.append(btn('اجرای بازی در RetroArch','full',()=>{
      closeModal();if(confirm('RetroArch و هستهٔ مربوط باید نصب باشند. پوشهٔ System/BIOS را روی Download/AMretro/System قرار بده و دسترسی فایل‌ها و Overlay را در RetroArch فعال کن. ادامه می‌دهی؟'))call('retroarch',g.id);
    }));
    const row=$('div','row');row.append(btn('نمایش تروفی‌ها','secondary',()=>call('trophy',g.id)),btn('حذف از کتابخانه','danger',()=>{if(confirm('بازی حذف شود؟ فایل ذخیره حفظ خواهد شد.')){closeModal();call('removeGame',g.id);}}));content.append(row);showModal();
  }
  function trophyView(g){
    state.trophy=g;clear(content);modalHeader('تروفی‌های '+g.title);content.append($('p','muted','فعالیت محلی AMretro؛ روی همین دستگاه ذخیره می‌شود و نشان رسمی RetroAchievements نیست.'));
    const ms=Number(g.play_ms||0),sessions=Number(g.sessions||0),tiers=[['🥉','برنزی','یک بار اجرای بازی',sessions>=1],['🥈','نقره‌ای','۵ دقیقه فعالیت',ms>=5*60000],['🥇','طلایی','۳۰ دقیقه فعالیت',ms>=30*60000],['💎','پلاتینیوم','۲ ساعت فعالیت',ms>=120*60000]];
    tiers.forEach(([icon,name,goal,earned])=>{const r=$('div','trophy-row'+(earned?'':' locked'));r.append($('div','trophy-medal',icon));const v=$('div');v.append($('b','',name+(earned?'  ·  آزاد شده':'')),$('small','',goal));r.append(v);content.append(r);});
    content.append($('p','muted',`جلسه: ${sessions}  ·  زمان فعالیت: ${Math.floor(ms/60000)} دقیقه`));
    content.append($('h3','result-title','RetroAchievements  ·  دستاوردهای واقعی'));
    content.append($('p','muted','ابتدا بازی را با RetroArch و حساب خودت اجرا کن. شناسه را از نشانی صفحهٔ بازی در RetroAchievements وارد کن. تطبیق فایل بازی با این شناسه خودکار نیست.'));
    const userLabel=$('label','label','نام کاربری RetroAchievements'),user=$('input','input');user.type='text';user.autocomplete='off';user.placeholder='Username';user.value=g.ra_user||'';userLabel.append(user);content.append(userLabel);
    const keyLabel=$('label','label','Web API Key'),key=$('input','input');key.type='password';key.autocomplete='off';key.placeholder=g.ra_has_key?'کلید ذخیره شده؛ برای حفظ آن خالی بگذار':'Web API Key';keyLabel.append(key);content.append(keyLabel);
    const idLabel=$('label','label','شناسهٔ بازی در RetroAchievements'),id=$('input','input');id.type='number';id.inputMode='numeric';id.placeholder='مثلاً 14402';if(Number(g.ra_id)>0)id.value=String(g.ra_id);idLabel.append(id);content.append(idLabel);
    const row=$('div','row');row.append(btn('دریافت دستاوردها','',()=>{call('fetchRa',g.id,user.value,key.value,id.value);key.value='';toast('در حال دریافت دستاوردهای واقعی…');}),btn('دریافت کلید','secondary',()=>call('openRaHelp')));content.append(row);
    content.append($('p','muted','کلید روی دستگاه با Android Keystore رمزگذاری می‌شود و برای دریافت اطلاعات به سرویس RetroAchievements فرستاده می‌شود.'));
    const results=$('div','ra-results');results.id='ra-results';content.append(results);if(state.ra&&state.ra.id===g.id)showRa(state.ra);showModal();
  }
  function showRa(data){
    state.ra=data;const box=document.getElementById('ra-results');if(!box)return;clear(box);const rows=Array.isArray(data.achievements)?data.achievements:[],earned=rows.filter(x=>x.earned).length;
    box.append($('h3','result-title',`${data.title}  ·  ${data.console}`),$('p','muted',`${earned} از ${rows.length} دستاورد رسمی آزاد شده است. رتبه‌های برنز/نقره/طلا در این نمایش، طبقه‌بندی غیررسمی AMretro بر اساس امتیاز هستند.`));
    if(rows.length>0&&earned===rows.length)box.append($('div','notice','💎 پلاتینیوم نمایشی AMretro: همهٔ دستاوردهای دریافتی آزاد شده‌اند. این عنوان، نشان رسمی RetroAchievements نیست.'));
    rows.forEach(a=>{const el=$('div','ra-item'+(a.earned?'':' locked'));const tier=Number(a.points)<=5?'برنز':Number(a.points)<=15?'نقره':'طلا';el.append($('strong','',`${a.earned?'✓':'○'}  ${a.title}  ·  ${tier} / ${a.points} امتیاز`),$('p','',a.description||''));if(a.earned&&a.hardcore)el.append($('p','','Hardcore'));box.append(el);});
  }
  window.fromNative=(type,data)=>{
    if(type==='catalog'){state.catalog=Array.isArray(data)?data:[];state.error='';render();}
    else if(type==='catalogError'){state.error=String(data);render();}
    else if(type==='library'){state.library=Array.isArray(data)?data:[];render();}
    else if(type==='message')toast(String(data));
    else if(type==='trophy') {state.ra=null;trophyView(data);}
    else if(type==='ra')showRa(data);
  };
  render();if(typeof bridge.library==='function')bridge.library();
})();
