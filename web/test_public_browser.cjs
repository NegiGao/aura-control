// Offline regression test. Every API is mocked; no device or headset is contacted.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.AURA_PLAYWRIGHT || 'playwright');
(async () => {
  const browser = await chromium.launch({headless:true, channel:process.env.AURA_BROWSER_CHANNEL || 'chrome'});
  const page = await browser.newPage();
  const calls = [], errors = [];
  const usb = {serial:'TEST-USB', physical_serial:'TEST-DEVICE', transport:'usb'};
  const wifi = {serial:'192.0.2.18:5555', physical_serial:'TEST-DEVICE', transport:'wireless'};
  const idle = {running:false, recovering:false};
  let devices = [usb], active = idle, verified = [];
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
      if (url.pathname === '/api/devices') result = {devices};
      if (url.pathname === '/api/status') result = {streams:{'1':active,'2':idle,'3':idle}, verified_wireless:verified, discovery:{automatic:false,pending:false,running:false}};
      if (url.pathname === '/api/version') result = {version:'3.12.8',page_version:'3.12.8',projection_running:active.running || active.recovering};
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
    await panel.locator('[data-app="youtube"]').waitFor({state:'visible'});
    assert.deepEqual(await panel.locator('[data-app]').evaluateAll(nodes => nodes.map(n=>n.dataset.app)), ['youtube','google-maps','maps-new']);
    assert.equal(await panel.locator('[data-destination]').count(), 4);
    assert.equal(await panel.locator('[data-tune="gemini-xr-off"]').count(), 1);
    assert.equal(await panel.locator('[data-action="arrange"]').count(), 1);
    assert.equal(await panel.locator('[data-action="recenter"]').count(), 1);
    assert.equal(await panel.locator('[data-field="savedSite"] option').count(), 1);
    await panel.locator('[data-app="youtube"]').click();
    assert(calls.some(x=>x.path==='/api/launch' && x.body.app==='youtube' && x.body.target==='TEST-USB' && x.body.physical_serial==='TEST-DEVICE'));
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
    await page.waitForFunction(() => selectedPhysical['1'] === 'TEST-DEVICE');
    const casting = device => ({running:true,recovering:false,physical_serial:device.physical_serial,active_serial:device.serial,active_connection:device.transport,audio:'no-audio'});
    active = casting(usb);
    await page.evaluate(() => updateStatus());
    active = {running:false,recovering:true,physical_serial:'TEST-DEVICE'};
    await page.evaluate(() => updateStatus());
    devices = [{...usb, physical_serial:'OTHER-DEVICE'}];
    await page.evaluate(() => updateDevices());
    assert.equal(await page.evaluate(() => selectedPhysical['1']), 'TEST-DEVICE');
    assert(await page.evaluate(() => { try { bindingFor('1'); return false; } catch { return true; } }));
    for (const [language,label] of [['ja','投影を再接続中'],['zh','正在恢复投屏'],['en','Reconnecting cast']]) {
      await page.locator(`[data-language="${language}"]`).click();
      assert.equal(await panel.locator('[role="status"]').textContent(),label);
    }
    devices = [usb,wifi];
    verified = [{serial:wifi.physical_serial,address:wifi.serial}];
    await page.evaluate(() => updateDevices());
    await page.evaluate(() => updateStatus());
    assert(await page.locator('#aura-connection-dialog').isHidden(), 'recovery does not show the first connection dialog');
    active = casting(wifi);
    await page.evaluate(() => updateStatus());
    assert.equal(await page.evaluate(() => bindingFor('1').target),wifi.serial);
    active = casting(usb);
    await page.evaluate(() => updateStatus());
    assert.equal(await page.evaluate(() => bindingFor('1').target),usb.serial);
    assert.deepEqual(errors, []);
    console.log('PASS: public Google shortcuts, bookmarks isolated, safe selected-device routing and Wi-Fi/USB recovery, 3 languages and responsive layout');
  } finally { await browser.close(); }
})().catch(e=>{console.error(e);process.exit(1);});
