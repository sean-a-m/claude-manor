// Dependency-free checks of the actual scene.js in headless Chromium via CDP.
// Start your own mock/offline/test panel on a free 8090–8099 port first.
// node test/manor/scene_client_test.mjs http://127.0.0.1:8097
import {spawn} from 'node:child_process';
import {mkdtemp, readFile, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import assert from 'node:assert/strict';

const base = process.argv[2];
assert.match(base || '', /^http:\/\/(127\.0\.0\.1|localhost):809[0-9]$/);
const profile = await mkdtemp(join(tmpdir(), 'manor-scene-browser-'));
const browser = spawn(process.env.CHROMIUM || 'chromium', ['--headless', '--no-sandbox', '--disable-gpu', '--disable-extensions',
  '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'], {stdio: 'ignore'});
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let ws;
try {
  let port;
  for (let i = 0; i < 100; i++) {
    try { port = Number((await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]); break; }
    catch (_) { await sleep(100); }
  }
  assert.ok(port, 'Chromium started');
  const targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
  const target = targets.find(t => t.type === 'page' && t.url === 'about:blank');
  assert.ok(target, `blank page target: ${JSON.stringify(targets)}`);
  ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise(resolve => ws.addEventListener('open', resolve, {once: true}));
  let id = 0;
  const pending = new Map(), errors = [];
  ws.addEventListener('message', ({data}) => {
    const message = JSON.parse(data);
    if (message.id) {
      const call = pending.get(message.id); pending.delete(message.id);
      if (message.error) call.reject(new Error(JSON.stringify(message.error))); else call.resolve(message.result);
    }
    if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails.text);
  });
  const cdp = (method, params = {}) => new Promise((resolve, reject) => {
    const n = ++id; pending.set(n, {resolve, reject}); ws.send(JSON.stringify({id: n, method, params}));
  });
  const evaluate = async expression => {
    const result = await cdp('Runtime.evaluate', {expression, awaitPromise: true, returnByValue: true});
    if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails));
    return result.result.value;
  };
  const waitFor = async expression => {
    for (let i = 0; i < 100; i++) { if (await evaluate(expression)) return; await sleep(50); }
    throw new Error(`Timed out: ${expression}; errors=${JSON.stringify(errors)}; page=${await evaluate('document.documentElement.outerHTML.slice(-2500)')}`);
  };
  await cdp('Runtime.enable');
  await cdp('Emulation.setDeviceMetricsOverride', {width: 1280, height: 900, deviceScaleFactor: 1, mobile: false});
  await cdp('Page.navigate', {url: base + '/'});
  await waitFor('!!window.ManorScene');
  assert.equal(await evaluate('document.querySelectorAll("#scene-map").length'), 1);
  if (process.env.SCENE_SCREENSHOT) {
    const shot = await cdp('Page.captureScreenshot', {format: 'png'});
    await writeFile(process.env.SCENE_SCREENSHOT, Buffer.from(shot.data, 'base64'));
  }
  await evaluate('document.querySelector("[data-kind=agent]").dispatchEvent(new MouseEvent("click", {bubbles:true}))');
  const selection = await evaluate('ManorScene.selection');
  assert.equal(selection.kind, 'agent');
  assert.ok(await evaluate('document.querySelector(".person.selected") !== null'));
  const initialBox = await evaluate('document.getElementById("scene-map").getAttribute("viewBox")');
  await evaluate('document.querySelector("[data-view=in]").click()');
  const zoomed = await evaluate('document.getElementById("scene-map").getAttribute("viewBox")');
  assert.notEqual(zoomed, initialBox, 'zoom changes viewBox');
  await evaluate('ManorScene.refresh()'); await sleep(400);
  assert.equal(await evaluate('document.getElementById("scene-map").getAttribute("viewBox")'), zoomed, 'refresh preserves viewport');
  assert.deepEqual(await evaluate('ManorScene.selection'), selection, 'refresh preserves selection');
  await evaluate('document.querySelector("[data-region-target]").click()');
  await evaluate('document.querySelector("[data-view=fit]").click()');
  assert.equal(await evaluate('document.getElementById("scene-map").getAttribute("viewBox")'), initialBox);

  const center = await evaluate(`(() => { const r=document.querySelector('[data-kind=place] rect').getBoundingClientRect(); return {x:r.x+r.width/2,y:r.y+r.height/2}; })()`);
  const mouse = (type, x, y, extra = {}) => cdp('Input.dispatchMouseEvent', {type, x, y, ...extra});
  await mouse('mouseWheel', center.x, center.y, {deltaY: -100, deltaX: 0});
  const wheelBox = await evaluate('document.getElementById("scene-map").getAttribute("viewBox")');
  assert.notEqual(wheelBox, initialBox, 'actual wheel handler');
  await mouse('mousePressed', center.x, center.y, {button: 'left', clickCount: 1});
  await mouse('mouseMoved', center.x + 50, center.y + 30, {button: 'left', buttons: 1});
  await mouse('mouseReleased', center.x + 50, center.y + 30, {button: 'left', clickCount: 1});
  assert.notEqual(await evaluate('document.getElementById("scene-map").getAttribute("viewBox")'), wheelBox, 'actual drag pans');
  assert.deepEqual(await evaluate('ManorScene.selection'), selection, 'click after drag is suppressed');
  // A normal pointer tap (not just a synthetic click) must still select.
  const tap = await evaluate(`(() => { const r=document.querySelector('[data-kind=place] rect').getBoundingClientRect(); return {x:r.x+r.width/2,y:r.y+r.height*0.2}; })()`);
  await mouse('mousePressed', tap.x, tap.y, {button: 'left', clickCount: 1});
  await mouse('mouseReleased', tap.x, tap.y, {button: 'left', clickCount: 1});
  assert.equal((await evaluate('ManorScene.selection')).kind, 'place', 'actual pointer tap selects');

  if (await evaluate('!!document.querySelector(".connection.stair")')) {
    await evaluate(`document.querySelector('.connection.stair').dispatchEvent(new PointerEvent('pointerover',{bubbles:true}))`);
    assert.equal(await evaluate('document.querySelectorAll(".stair-partner").length'), 2, 'stair hover highlights both rooms');
    await evaluate(`document.querySelector('.connection.stair').dispatchEvent(new PointerEvent('pointerout',{bubbles:true}))`);
    assert.equal(await evaluate('document.querySelectorAll(".stair-partner").length'), 0);
  }

  // Existing controls and real SSE: command submission must refresh toolbar.
  const beforeRound = await evaluate('document.getElementById("scene-toolbar").textContent');
  await evaluate(`document.querySelector('form[action="/cmd/step"]').requestSubmit()`);
  await waitFor(`document.getElementById('scene-toolbar').textContent !== ${JSON.stringify(beforeRound)}`);
  await sleep(700);
  assert.ok(await evaluate('document.querySelectorAll("#feed li").length <= 70'));
  const beforeSSE = await evaluate('document.getElementById("scene-toolbar").textContent');
  await evaluate(`fetch('/cmd/step', {method:'POST'})`);
  await waitFor(`document.getElementById('scene-toolbar').textContent !== ${JSON.stringify(beforeSSE)}`);
  await sleep(500);

  // Deliberately ignore AbortSignals to verify generation checks as well as abort.
  await evaluate('document.querySelector("[data-region-target]").click()');
  const anchored = (await evaluate('document.getElementById("scene-map").getAttribute("viewBox")')).split(' ').map(Number);
  await evaluate(`window.originalFetch=window.fetch; window.requests=[];
    window.fetch=(url,opts)=>new Promise(resolve=>requests.push({url,resolve}));`);
  await evaluate('ManorScene.refresh()'); await waitFor('requests.length===1');
  await evaluate('ManorScene.refresh()'); await waitFor('requests.length===2');
  await evaluate(`(async()=>{window.state=await (await originalFetch('/partial/scene-state')).json();
    state.toolbar='<b>new response</b>';
    const doc=new DOMParser().parseFromString(state.scene,'image/svg+xml');
    const target=document.querySelector('[data-region-target]').dataset.regionTarget;
    const region=Array.from(doc.querySelectorAll('[data-region]')).find(n=>n.dataset.region===target);
    const bounds=region.dataset.box.split(' ').map(Number); bounds[0]+=7; bounds[1]-=3;
    region.dataset.box=bounds.join(' '); state.scene=new XMLSerializer().serializeToString(doc.documentElement);
    requests[1].resolve({ok:true,json:async()=>state});})()`);
  await waitFor('document.getElementById("scene-toolbar").textContent==="new response"');
  const shifted = (await evaluate('document.getElementById("scene-map").getAttribute("viewBox")')).split(' ').map(Number);
  assert.ok(Math.abs(shifted[0] - anchored[0] - 7) < 1e-8 && Math.abs(shifted[1] - anchored[1] + 3) < 1e-8, 'region anchor follows outer packing shift');
  await evaluate(`requests[0].resolve({ok:true,json:async()=>({...state,toolbar:'stale response'})})`);
  await sleep(100);
  assert.equal(await evaluate('document.getElementById("scene-toolbar").textContent'), 'new response', 'stale refresh ignored');
  await evaluate(`requests=[]; document.getElementById('scene-page').dataset.inspectBase='/partial/inspect/';
    ManorScene.select({kind:'place',id:'odd /"<>&?[]'});`);
  await waitFor('requests.length===1');
  assert.equal(await evaluate('requests[0].url'), '/partial/inspect/place/' + encodeURIComponent('odd /"<>&?[]'));
  assert.equal(await evaluate('document.getElementById("inspector").children.length'), 0, 'selection uses textContent');
  await evaluate(`ManorScene.select({kind:'agent',id:'new-selection'}); requests[1].resolve({ok:true,text:async()=>'<b>current inspector</b>'});`);
  await waitFor('document.getElementById("inspector").textContent==="current inspector"');
  await evaluate(`requests[0].resolve({ok:true,text:async()=>'<b>stale inspector</b>'})`);
  await sleep(100);
  assert.equal(await evaluate('document.getElementById("inspector").textContent'), 'current inspector', 'stale selection ignored');
  await evaluate(`ManorScene.select({kind:'place',id:'deleted'}); requests[2].resolve({ok:false,text:async()=>''});`);
  await waitFor('document.getElementById("inspector").textContent==="Selection no longer available."');
  await evaluate(`ManorScene.select(null); window.fetch=originalFetch; delete document.getElementById('scene-page').dataset.inspectBase; ManorScene.refresh();`);
  await sleep(300);
  assert.equal(await evaluate('ManorScene.selection'), null);
  assert.deepEqual(errors, [], 'no browser runtime exceptions');
  console.log('PASS actual scene client: selection/escaping, wheel/pan/fit, tap/drag suppression, SSE/control refresh, bounded feed, viewport/selection preservation, stale refresh/inspector guards, missing selection.');
} finally {
  if (ws) ws.close();
  if (browser.exitCode === null) {
    const exited = new Promise(resolve => browser.once('exit', resolve));
    browser.kill('SIGTERM');
    await exited;
  }
  await rm(profile, {recursive: true, force: true});
}
