"""Isolated Linux end-to-end test: native WebView, HTTP catalog, BIOS, real RetroArch.

Run under Xvfb. Needs gcc, xdotool, retroarch, WebKitWebDriver and tauri-driver.
Uses only generated test bytes and the original input_probe.c fixture, not games
or copyrighted BIOS. Never contacts/modifies the production catalog.
"""
import hashlib, json, os, shutil, signal, socket, sqlite3, subprocess, tempfile, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit
import requests

binary=os.environ.get('AMRETRO_BINARY','/opt/retro-target/debug/retro-hub-desktop')
driver_bin=os.environ.get('TAURI_DRIVER','/opt/amretro-tmp/driver/bin/tauri-driver')
root=Path(tempfile.mkdtemp(prefix='amretro-auto-e2e-',dir=os.environ.get('AMRETRO_TEST_TMP','/opt/amretro-tmp')))
source=Path(__file__).resolve().parent/'fixtures/input_probe.c'
bios_data=(b'AMRETRO SYNTHETIC BIOS - NOT A REAL BIOS\n'*16000)[:600*1024]
game_bytes={name:(f'{name} SYNTHETIC GAME FIXTURE').encode() for name in ('game1','game2','game3','game4','game5','game6')}
stats={};mutex=threading.Lock();session=None;driver=None;server=None

def free_port():
 with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
def sha(x):return hashlib.sha256(x).hexdigest()

class Handler(BaseHTTPRequestHandler):
 def log_message(self,*a):pass
 def respond(self,data,status=200,ctype='application/json'):
  body=json.dumps(data).encode() if isinstance(data,(dict,list)) else data
  start=0;end=len(body)-1;partial=False
  if self.headers.get('Range','').startswith('bytes='):
   a,_,b=self.headers['Range'][6:].partition('-');start=int(a or 0);end=min(int(b),end) if b else end;partial=True
  if start>=len(body):
   self.send_response(416);self.send_header('Content-Range',f'bytes */{len(body)}');self.send_header('Content-Length','0');self.end_headers();return
  self.send_response(206 if partial else status);self.send_header('Content-Type',ctype);self.send_header('Accept-Ranges','bytes');self.send_header('Content-Length',str(end-start+1))
  if partial:self.send_header('Content-Range',f'bytes {start}-{end}/{len(body)}')
  self.end_headers()
  if self.command!='HEAD':
   with mutex:stats[self.path]=stats.get(self.path,0)+1
   try:self.wfile.write(body[start:end+1])
   except (BrokenPipeError,ConnectionResetError):pass
 def do_HEAD(self):self.do_GET()
 def do_GET(self):
  path=urlsplit(self.path).path;base=f'http://127.0.0.1:{self.server.server_port}'
  console={'id':'ps1','name':'PlayStation test','slug':'ps1','core':'probe','extensions':'bin','icon_url':''}
  games=[{'id':name,'title':name,'console_id':'ps1','console_name':'PlayStation test','console_core':'probe','file_name':f'{name}.bin','download_url':f'{base}/files/{name}.bin','sha256':sha(data),'size_bytes':len(data),'images':[]} for name,data in game_bytes.items()]
  bios={'id':'bios1','name':'Synthetic test BIOS','console_id':'ps1','console_name':'PlayStation test','file_name':'bios.bin','system_path':'scph5501.bin','download_url':f'{base}/files/bios.bin','sha256':sha(bios_data)}
  if path=='/api/consoles':self.respond({'data':[console]})
  elif path=='/api/games':self.respond({'data':games,'total':len(games)})
  elif path.startswith('/api/games/'):
   game=next((g for g in games if g['id']==path.split('/')[-1]),None);self.respond({'data':game} if game else {'error':'missing'},200 if game else 404)
  elif path=='/api/bios':self.respond({'data':[bios]})
  elif path.startswith('/api/cheats/'):self.respond({'data':[]})
  elif path=='/files/bios.bin':
   if self.command!='HEAD':time.sleep(.25)
   self.respond(bios_data,ctype='application/octet-stream')
  elif path.startswith('/files/') and path.endswith('.bin'):
   item=path.rsplit('/',1)[-1][:-4];self.respond(game_bytes.get(item,b'?'),ctype='application/octet-stream')
  else:self.respond({'error':'not found'},404)

def wait_for(fn,timeout=45):
 end=time.monotonic()+timeout;last=None
 while time.monotonic()<end:
  try:
   last=fn()
   if last:return last
  except (requests.RequestException,AssertionError,KeyError):pass
  time.sleep(.2)
 raise AssertionError(f'Condition not met; last={last!r}')

def wd(method,path='',**kw):
 r=requests.request(method,f'http://127.0.0.1:{port}/session'+('/'+session if session else '')+path,timeout=50,**kw)
 r.raise_for_status();j=r.json().get('value')
 if isinstance(j,dict) and 'error' in j:raise RuntimeError(j)
 return j

def js(script,*args):return wd('POST','/execute/sync',json={'script':script,'args':list(args)})
def selector(sel):return js('return !!document.querySelector(arguments[0]);',sel)
def click(sel):assert js('const e=document.querySelector(arguments[0]);if(!e)return false;e.click();return true;',sel),sel
def stop(p):
 if p and p.poll() is None:
  p.terminate()
  try:p.wait(timeout=8)
  except subprocess.TimeoutExpired:p.kill();p.wait()

try:
 (root/'cores').mkdir();(root/'system').mkdir();(root/'config/retroarch').mkdir(parents=True)
 core=root/'cores/probe_libretro.so';subprocess.run(['gcc','-shared','-fPIC',str(source),'-o',str(core)],check=True)
 (root/'config/retroarch/retroarch.cfg').write_text('audio_enable = "false"\nvideo_fullscreen = "false"\nvideo_driver = "gl"\ninput_driver = "x"\n')
 server=ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start();base=f'http://127.0.0.1:{server.server_port}'
 port=free_port();native_port=free_port();log=(root/'driver.log').open('w')
 env=os.environ.copy();env.update({'XDG_DATA_HOME':str(root/'data'),'XDG_CONFIG_HOME':str(root/'config'),'XDG_CACHE_HOME':str(root/'cache'),'AMRETRO_PROBE_LOG':str(root/'probe.log'),'WEBKIT_DISABLE_DMABUF_RENDERER':'1','NO_AT_BRIDGE':'1','GDK_BACKEND':'x11'})
 driver=subprocess.Popen([driver_bin,'--port',str(port),'--native-port',str(native_port)],env=env,stdout=log,stderr=log)
 wait_for(lambda:requests.get(f'http://127.0.0.1:{port}/status',timeout=1).ok,timeout=35)
 caps={'capabilities':{'alwaysMatch':{'browserName':'wry','tauri:options':{'application':binary}}}}
 session=wd('POST',json=caps)['sessionId'];wait_for(lambda:selector('[data-nav="settings"]'),timeout=60)
 click('#nav [data-nav="settings"]');wait_for(lambda:selector('#settings-form'))
 paths={'api_url':base,'games_dir':str(root/'games'),'retroarch_path':'/usr/bin/retroarch','cores_dir':str(root/'cores'),'system_dir':str(root/'system')}
 js('''for(const [k,v] of Object.entries(arguments[0])){const e=document.getElementById(k);e.value=v;e.dispatchEvent(new Event('input',{bubbles:true}));}document.querySelector('#settings-form').requestSubmit();''',paths)
 wait_for(lambda:js('return document.querySelector("#toast")?.textContent.includes("تنظیمات ذخیره شد")'))
 click('#nav [data-nav="catalog"]');wait_for(lambda:selector('[data-game="game1"]'),timeout=60)
 def install(game):
  click(f'[data-game="{game}"]');wait_for(lambda:selector(f'[data-install="{game}"]'));click(f'[data-install="{game}"]');wait_for(lambda:selector(f'[data-launch="{game}"]'),timeout=90)
 install('game1');assert (root/'system/scph5501.bin').read_bytes()==bios_data
 with mutex:first=stats.get('/files/bios.bin',0)
 assert first>0,stats
 click('#close-dialog');install('game2')
 with mutex:second=stats.get('/files/bios.bin',0)
 assert first==second,(first,second,stats)
 assert (root/'games/game1/game1.bin').read_bytes()==game_bytes['game1'];assert (root/'games/game2/game2.bin').read_bytes()==game_bytes['game2']
 print('PASS: first PS1 game installed BIOS; second checked it and did not re-download.')
 # Simulate an older installation that has a valid BIOS file but no manifest.
 db=next((root/'data').rglob('retro-hub.sqlite3'))
 with sqlite3.connect(db) as conn:conn.execute('DELETE FROM bios_installs');conn.commit()
 click('#close-dialog');install('game3')
 with mutex:assert stats.get('/files/bios.bin',0)==first,stats
 print('PASS: pre-existing matching BIOS without a manifest was reused, without downloading.')
 # Two installs starting together after the BIOS was removed must share one
 # BIOS installation, then each download its own game.
 click('#close-dialog');(root/'system/scph5501.bin').unlink()
 with sqlite3.connect(db) as conn:conn.execute('DELETE FROM bios_installs');conn.commit()
 js('''window.__biosEvents=[];window.__invokeErr='';window.__TAURI__.event.listen('download-progress',e=>window.__biosEvents.push(e.payload)).then(()=>{for(const gameId of ['game4','game5'])window.__TAURI__.core.invoke('start_install',{gameId}).catch(e=>window.__invokeErr=String(e));});return true;''')
 wait_for(lambda:(root/'games/game4/game4.bin').is_file() and (root/'games/game5/game5.bin').is_file(),timeout=90)
 assert not js('return window.__invokeErr'),js('return window.__invokeErr')
 events=js('return window.__biosEvents.filter(x=>x.kind==="bios" && x.state==="starting").length')
 assert events==1,events
 assert (root/'system/scph5501.bin').read_bytes()==bios_data
 print('PASS: concurrent game installs serialized a single BIOS download.')
 # Configure the game's A on keyboard P, using logical controller B as the
 # source (with the reciprocal B/A swap); then launch through real AMretro.
 click('[data-game="game1"]');wait_for(lambda:selector('[data-control-game="game1"]'));click('[data-control-game="game1"]');wait_for(lambda:selector('[data-bind-key="a"]'))
 click('[data-bind-key="a"]');js('document.dispatchEvent(new KeyboardEvent("keydown",{key:"p",code:"KeyP",bubbles:true}));')
 js('const e=document.querySelector("[data-bind-pad=a]");e.value="b";e.dispatchEvent(new Event("change",{bubbles:true}));')
 assert js('return document.querySelector("[data-bind-pad=b]").value')=='a'
 click('[data-controls-action="save"]');wait_for(lambda:js('return document.querySelector("#toast")?.textContent.includes("دکمه‌ها ذخیره شدند")'))
 click('#nav [data-nav="library"]');wait_for(lambda:selector('[data-game="game1"]'));click('[data-game="game1"]');wait_for(lambda:selector('[data-launch="game1"]'));click('[data-launch="game1"]')
 wait_for(lambda:js('return document.querySelector("#toast")?.textContent.includes("RetroArch اجرا شد")'))
 def retro_window():
  q=subprocess.run(['xdotool','search','--onlyvisible','--name','RetroArch'],capture_output=True,text=True)
  return q.stdout.strip().splitlines()[0] if q.returncode==0 and q.stdout.strip() else None
 wid=wait_for(retro_window,timeout=30);subprocess.run(['xdotool','windowfocus','--sync',wid],check=True);time.sleep(1)
 subprocess.run(['xdotool','keydown','p'],check=True);time.sleep(.7);subprocess.run(['xdotool','keyup','p'],check=True)
 probe=root/'probe.log';wait_for(lambda:probe.exists() and 'B=0 A=1' in probe.read_text(),timeout=10)
 assert 'B=1 A=0' not in probe.read_text(),probe.read_text()
 remaps=list((root/'data').rglob('game1.rmp'));assert len(remaps)==1,remaps
 text=remaps[0].read_text();assert 'input_player1_btn_b = "8"' in text and 'input_player1_btn_a = "0"' in text,text
 print('PASS: real RetroArch loaded AMretro per-game remap; simulated key P reached core as A, not B.')
 # Close only the test RetroArch process, then tamper with the test BIOS. No
 # user file is touched and no conflicting game download should begin.
 for pid in subprocess.run(['pgrep','-f',str(root/'games/game1/game1.bin')],capture_output=True,text=True).stdout.split():
  try:os.kill(int(pid),signal.SIGTERM)
  except ProcessLookupError:pass
 (root/'system/scph5501.bin').write_bytes(b'changed fixture that must not be overwritten')
 click('#close-dialog');click('#nav [data-nav="catalog"]');click('[data-game="game6"]');wait_for(lambda:selector('[data-install="game6"]'));click('[data-install="game6"]')
 wait_for(lambda:js('return document.querySelector("#toast")?.textContent.includes("بازنویسی نمی‌شود")'),timeout=60)
 assert (root/'system/scph5501.bin').read_bytes()==b'changed fixture that must not be overwritten'
 assert not (root/'games/game6').exists()
 with mutex:assert stats.get('/files/game6.bin',0)==0,stats
 print('PASS: changed BIOS is preserved; game installation stops before game download.')
finally:
 if session:
  try:wd('DELETE')
  except Exception:pass
 stop(driver)
 if server:server.shutdown();server.server_close()
 try:log.close()
 except Exception:pass
 if os.environ.get('AMRETRO_KEEP_TEST'):
  print('Test artifacts:',root)
 else:shutil.rmtree(root,ignore_errors=True)
