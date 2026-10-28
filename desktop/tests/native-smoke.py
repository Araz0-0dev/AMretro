"""Open a real Linux AMretro WebView and verify live catalog + old library data.

Run under Xvfb with AMRETRO_BINARY pointing at the executable extracted from
its .deb. Optionally set AMRETRO_OLD_BINARY to a prior Retro Hub build to test
an actual upgrade without touching the user's library.
"""
import glob
import json
import os
import sqlite3
import subprocess
import tempfile
import time
from pathlib import Path

binary = os.environ['AMRETRO_BINARY']
old_binary = os.environ.get('AMRETRO_OLD_BINARY')
base = Path(tempfile.mkdtemp(prefix='amretro-native-', dir=os.environ.get('AMRETRO_TEST_TMP', '/tmp')))
env = os.environ.copy()
env.update({
    'XDG_DATA_HOME': str(base / 'data'), 'XDG_CONFIG_HOME': str(base / 'config'),
    'XDG_CACHE_HOME': str(base / 'cache'), 'WEBKIT_DISABLE_DMABUF_RENDERER': '1',
    'NO_AT_BRIDGE': '1', 'GDK_BACKEND': 'x11',
})

def stop(process):
    process.terminate()
    try: process.wait(timeout=8)
    except subprocess.TimeoutExpired: process.kill(); process.wait()

def wait_window(title, process):
    end = time.monotonic() + 40
    while time.monotonic() < end:
        result = subprocess.run(['xdotool', 'search', '--name', title], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        if result.returncode == 0 and result.stdout.strip(): return result.stdout.strip().splitlines()[0]
        if process.poll() is not None: raise RuntimeError('application closed before opening its window')
        time.sleep(.2)
    raise RuntimeError('window not found: ' + title)

def wait_database():
    end = time.monotonic() + 35
    while time.monotonic() < end:
        files = glob.glob(str(base / 'data' / '**' / 'retro-hub.sqlite3'), recursive=True)
        if files:
            try:
                with sqlite3.connect(files[0]) as c:
                    row = c.execute('SELECT json FROM catalog_cache WHERE id=1').fetchone()
                if row: return Path(files[0]), json.loads(row[0])
            except sqlite3.Error: pass
        time.sleep(.25)
    raise RuntimeError('live catalog was not synchronized')

try:
    if old_binary:
        old = subprocess.Popen([old_binary], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            wait_window(os.environ.get('AMRETRO_OLD_TITLE', 'Retro Hub'), old)
            old_db, catalog = wait_database()
            with sqlite3.connect(old_db) as c:
                c.execute("INSERT INTO settings(key,value) VALUES('games_dir',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", (str(base / 'previous-games'),))
                c.execute("INSERT INTO game_preferences(game_id,preset) VALUES('preserved-test','quality') ON CONFLICT(game_id) DO UPDATE SET preset=excluded.preset")
                c.execute('DELETE FROM catalog_cache')
                c.commit()
        finally: stop(old)
    app = subprocess.Popen([binary], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    try:
        window = wait_window('AMretro', app)
        db, catalog = wait_database()
        assert len(catalog['consoles']) >= 10
        if old_binary:
            assert db == old_db, (db, old_db)
            with sqlite3.connect(db) as c:
                assert c.execute("SELECT value FROM settings WHERE key='games_dir'").fetchone()[0] == str(base / 'previous-games')
                assert c.execute("SELECT preset FROM game_preferences WHERE game_id='preserved-test'").fetchone()[0] == 'quality'
        # Save an authentic native-window screenshot only as a diagnostic.
        time.sleep(1)
        capture = os.environ.get('AMRETRO_NATIVE_SCREENSHOT')
        if capture: subprocess.run(['import', '-window', 'root', capture], check=False)
        print('Native AMretro window:', window, '; live consoles:', len(catalog['consoles']), '; games:', len(catalog['games']))
        if old_binary: print('Previous SQLite path, games directory and per-game preset preserved.')
    finally: stop(app)
finally:
    import shutil
    shutil.rmtree(base, ignore_errors=True)
