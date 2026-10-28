"""Linux native WebDriver E2E against the configured Worker.

Requires tauri-driver + WebKitWebDriver + Xvfb and a running WebDriver on 4444.
Supply RETRO_ADMIN_PASSWORD. Only unique temporary records are created/deleted.
Set RETRO_E2E_BINARY if the test app is not at the default path.
"""
import base64, hashlib, io, json, os, secrets, tempfile, time, zipfile
from pathlib import Path
import requests

BASE=os.environ.get('RETRO_BASE','https://retro-hub-68aa04.am0u0dev.workers.dev').rstrip('/')
AUTH=('admin',os.environ['RETRO_ADMIN_PASSWORD'])
DRIVER=os.environ.get('RETRO_WEBDRIVER','http://127.0.0.1:4444').rstrip('/')
BINARY=os.environ.get('RETRO_E2E_BINARY','/opt/retro-target/debug/retro-hub-desktop')
PNG=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jR9sAAAAASUVORK5CYII=')
keys=[]; game_id=None; bios_id=None; session=None
http=requests.Session();http.auth=AUTH

def req(method,path,**kw):
 r=http.request(method,BASE+path,timeout=45,**kw)
 if r.status_code not in (200,201):raise RuntimeError(f'{method} {path}: HTTP {r.status_code} {r.text[:500]}')
 return r.json()
def upload_file(content,category):
 j=req('POST','/api/admin/uploads/file',headers={'Origin':BASE,'Content-Type':'application/octet-stream','X-Retro-Upload':'1','X-File-Category':category},data=content)['data'];keys.append(j['key']);return j['key']
def upload_image(category):
 j=req('POST','/api/admin/uploads/image',headers={'Origin':BASE,'Content-Type':'image/png','X-Retro-Upload':'1','X-Image-Category':category},data=PNG)['data'];keys.append(j['key']);return j['key']
def wd(method,path,**kw):
 r=requests.request(method,DRIVER+'/session'+('/'+session if session else '')+path,timeout=55,**kw)
 if not r.ok:raise RuntimeError(f'WebDriver {r.status_code}: {r.text[:1200]}')
 j=r.json();
 if isinstance(j,dict) and 'value' in j and isinstance(j['value'],dict) and 'error' in j['value']:raise RuntimeError(j['value'])
 return j.get('value')
def execute(js,*args):return wd('POST','/execute/sync',json={'script':js,'args':list(args)})
def wait_js(js,*args,timeout=45):
 end=time.monotonic()+timeout;last=None
 while time.monotonic()<end:
  last=execute(js,*args)
  if last:return last
  time.sleep(.25)
 raise AssertionError(f'WebDriver condition not met: {js} last={last!r}')
def click(selector):
 ok=execute('const e=document.querySelector(arguments[0]);if(!e)return false;e.click();return true;',selector)
 assert ok,'Missing: '+selector

test_root=Path(tempfile.mkdtemp(prefix='retro-native-test-'))
try:
 rom=secrets.token_bytes(240000);buffer=io.BytesIO()
 with zipfile.ZipFile(buffer,'w',zipfile.ZIP_DEFLATED) as z:z.writestr('nested/demo.nes',rom)
 game_bytes=buffer.getvalue();bios_bytes=secrets.token_bytes(600*1024)
 game_key=upload_file(game_bytes,'games');bios_key=upload_file(bios_bytes,'bios')
 cover_key=upload_image('covers');icon_key=upload_image('icons');gallery_key=upload_image('screenshots')
 game_id=req('POST','/api/admin/games',json={'title':'Temporary native desktop test','console_id':'nes','source':'cloudflare','storage_key':game_key,'file_name':'demo.zip','size_bytes':len(game_bytes),'sha256':hashlib.sha256(game_bytes).hexdigest(),'cover_key':cover_key,'icon_key':icon_key,'published':1})['data']['id']
 req('POST',f'/api/admin/games/{game_id}/images',json={'storage_key':gallery_key,'alt_text':'test gallery'})
 bios_id=req('POST','/api/admin/bios',json={'name':'Temporary native BIOS test','console_id':'ps1','source':'cloudflare','storage_key':bios_key,'file_name':'scph5501.bin','system_path':'scph5501.bin','size_bytes':len(bios_bytes),'sha256':hashlib.sha256(bios_bytes).hexdigest(),'published':1})['data']['id']
 capabilities={'capabilities':{'alwaysMatch':{'browserName':'wry','tauri:options':{'application':BINARY}}}}
 session=wd('POST','',json=capabilities)['sessionId']
 wait_js('return !!window.__TAURI__ && !!document.querySelector(arguments[0]);',f'[data-game="{game_id}"]',timeout=60)
 print('Native WebView started; real Worker catalog is visible.')
 games=test_root/'games';cores=test_root/'cores';system=test_root/'system';cores.mkdir();system.mkdir();(cores/'nestopia_libretro.so').write_bytes(b'not a real core - launcher argument test')
 launcher=test_root/'fake-retroarch';argfile=test_root/'launch-args.txt';launcher.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "'+str(argfile)+'"\n');launcher.chmod(0o755)
 click('[data-nav="settings"]');wait_js('return !!document.querySelector("#settings-form")')
 execute('''const paths=arguments[0];for(const [k,v] of Object.entries(paths)){const e=document.getElementById(k);e.value=v;e.dispatchEvent(new Event('input',{bubbles:true}));}document.querySelector('#settings-form').requestSubmit();return true;''',{'games_dir':str(games),'retroarch_path':str(launcher),'cores_dir':str(cores),'system_dir':str(system),'api_url':BASE})
 wait_js('return document.querySelector("#toast")?.textContent.includes("تنظیمات ذخیره شد")')
 click('[data-nav="home"]');wait_js('return !!document.querySelector(arguments[0]);',f'[data-game="{game_id}"]');click(f'[data-game="{game_id}"]')
 wait_js('return !!document.querySelector(arguments[0]);',f'[data-install="{game_id}"]')
 wait_js('return document.querySelector(".gallery img")?.naturalWidth > 0 && document.querySelector(".detail-cover img")?.naturalWidth > 0',timeout=40)
 click(f'[data-install="{game_id}"]');wait_js('return !!document.querySelector(arguments[0]);',f'[data-launch="{game_id}"]',timeout=100)
 installed=games/game_id/'nested/demo.nes';assert installed.read_bytes()==rom,'ROM differs after download/extract'
 execute('let e=document.querySelector("#preset");e.value="quality";e.dispatchEvent(new Event("change",{bubbles:true}));return true;')
 wait_js('return document.querySelector("#toast")?.textContent.includes("حالت بازی ذخیره شد")')
 click(f'[data-launch="{game_id}"]');wait_js('return document.querySelector("#toast")?.textContent.includes("RetroArch اجرا شد")')
 end=time.monotonic()+10
 while not argfile.exists() and time.monotonic()<end:time.sleep(.1)
 args=argfile.read_text().splitlines();assert args[0]=='-L' and args[1]==str(cores/'nestopia_libretro.so') and args[2]==str(installed) and args[3]=='--appendconfig',args
 assert 'video_smooth = "true"' in Path(args[4]).read_text();print('Real desktop: artwork, gallery, ZIP download, SHA-256, extraction, preset and RetroArch CLI arguments verified.')
 click('#close-dialog');click('[data-nav="bios"]');wait_js('return !!document.querySelector(arguments[0]);',f'[data-bios="{bios_id}"]');click(f'[data-bios="{bios_id}"]')
 wait_js('return document.querySelector("#toast")?.textContent.includes("BIOS نصب شد")',timeout=100)
 assert (system/'scph5501.bin').read_bytes()==bios_bytes;print('Real desktop: 600-KiB BIOS downloaded from Cloudflare and installed byte-for-byte.')
finally:
 if session:
  try:wd('DELETE','')
  except Exception as e:print('WebDriver cleanup:',str(e)[:150])
 if game_id:
  try:req('DELETE','/api/admin/games/'+game_id,json={})
  except Exception as e:print('Game cleanup failed:',e)
 if bios_id:
  try:req('DELETE','/api/admin/bios/'+bios_id,json={})
  except Exception as e:print('BIOS cleanup failed:',e)
 for key in keys:
  try:req('POST','/api/admin/media/delete',json={'key':key})
  except Exception as e:print('File cleanup failed:',e)
 import shutil;shutil.rmtree(test_root,ignore_errors=True)
