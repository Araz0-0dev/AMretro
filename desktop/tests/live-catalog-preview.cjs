// Produce a screenshot of the current, real public catalog without inventing games.
// Desktop IPC is mocked only for this screenshot; real native tests are separate.
const { chromium } = require('playwright');
const { pathToFileURL } = require('url');
const path = require('path');
(async () => {
  const base = 'https://retro-hub-68aa04.am0u0dev.workers.dev';
  const consoles = await (await fetch(base + '/api/consoles')).json();
  const games = await (await fetch(base + '/api/games?limit=100')).json();
  const catalog = { consoles: consoles.data, games: games.data };
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage({ viewport: { width: 1280, height: 860 }, deviceScaleFactor: 1 });
  await page.addInitScript(data => {
    window.__TAURI__ = {
      event: { listen: async () => () => {} },
      core: { invoke: async name => {
        if (name === 'sync_catalog') return data;
        if (name === 'list_library') return [];
        if (name === 'get_cached_catalog' || name === 'account_status') return null;
        if (name === 'get_settings') return { api_url: 'https://retro-hub-68aa04.am0u0dev.workers.dev', games_dir: '/games', retroarch_path: 'retroarch', cores_dir: '/cores', system_dir: '/system' };
        throw new Error('Not available in screenshot: ' + name);
      } }
    };
  }, catalog);
  await page.goto(pathToFileURL(path.resolve('ui/index.html')).href);
  await page.waitForFunction(() => document.querySelector('#side-status')?.textContent.includes('متصل'));
  await page.evaluate(() => document.fonts.ready);
  await page.screenshot({ path: path.resolve('../releases/AMretro-Preview.png'), fullPage: true });
  console.log(`Screenshot saved; live catalog: ${catalog.consoles.length} consoles, ${catalog.games.length} games.`);
  await browser.close();
})().catch(error => { console.error(error); process.exit(1); });
