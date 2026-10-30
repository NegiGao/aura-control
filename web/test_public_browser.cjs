// Offline regression test. Every API is mocked; no device or headset is contacted.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.AURA_PLAYWRIGHT || 'playwright');
(async () => {
  const browser = await chromium.launch({headless:true, channel:process.env.AURA_BROWSER_CHANNEL || 'chrome'});
  const page = await browser.newPage();
  const calls = [], errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    // An unrelated older edition's bookmark must never be imported or deleted.
    localStorage.setItem('projectionSavedSites', JSON.stringify(['https://private.example.invalid/']));
  });
  await page.route('**/*', async route => {
    const url = new URL(route.request().url());
    assert.equal(url.origin, 'http://aura.test');
    if (url.pathname.startsWith('/api/')) {
      const body = route.request().postDataJSON() || {};
      calls.push({path:url.pathname, body});
      let result = {success:true, serial:body.target, url:body.url};
      if (url.pathname === '/api/devices') result = {devices:[{serial:'TEST-USB', physical_serial:'TEST-DEVICE', transport:'usb'}]};
      if (url.pathname === '/api/status') result = {streams:{}, verified_wireless:[], discovery:{automatic:false,pending:false,running:false}};
      if (url.pathname === '/api/app-presence') result = {physical_serial:body.physical_serial, present:true};
      return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(result)});
    }
    const file = path.resolve(__dirname, url.pathname === '/' ? 'index.html' : url.pathname.slice(1));
    assert(file.startsWith(__dirname + path.sep));
    return route.fulfill({status:200,body:fs.readFileSync(file),contentType:file.endsWith('.js')?'text/javascript':'text/html'});
  });
  try {
    await page.goto('http://aura.test');
    const panel = page.locator('[data-slot="1"]');
    await panel.locator('[data-app="photos"]').waitFor({state:'visible'});
    assert.equal(await panel.locator('[data-app]').count(), 11);
    assert.equal(await panel.locator('[data-destination]').count(), 0);
    assert.equal(await panel.locator('[data-field="savedSite"] option').count(), 1);
    await panel.locator('[data-app="photos"]').click();
    assert(calls.some(x=>x.path==='/api/launch' && x.body.app==='photos' && x.body.target==='TEST-USB'));
    await panel.locator('[data-field="url"]').fill('example.org');
    await panel.locator('.url-save').click();
    assert.equal(await panel.locator('[data-field="savedSite"] option').count(), 2);
    assert.equal(await page.evaluate(()=>JSON.parse(localStorage.getItem('auraPublicSavedSitesV1'))[0]), 'https://example.org/');
    assert.equal(await page.evaluate(()=>JSON.parse(localStorage.getItem('projectionSavedSites'))[0]), 'https://private.example.invalid/');
    await panel.locator('.url-open').click();
    assert(calls.some(x=>x.path==='/api/open-url' && x.body.url==='https://example.org/' && x.body.target==='TEST-USB'));
    for (const language of ['zh','en','ja']) {
      await page.locator(`[data-language="${language}"]`).click();
      for (const width of [390,1280,1920]) {
        await page.setViewportSize({width,height:1000});
        assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth), false);
      }
    }
    await page.reload();
    assert.equal(await page.locator('[data-slot="1"] [data-field="savedSite"] option').count(), 2);
    assert.deepEqual(errors, []);
    console.log('PASS: 11 Google 2D shortcuts, selected-device routing, empty first-run bookmarks, preserved old storage, save/open/reload, 3 languages and responsive layout');
  } finally { await browser.close(); }
})().catch(e=>{console.error(e);process.exit(1);});
