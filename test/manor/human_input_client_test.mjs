// Dependency-free browser check of schema forms and real human-seat commands.
// Start only the mock fixture on your own free 8090–8099 port, for example:
// clojure -M:dev -e '(require (quote [manor.human-input-test :as t]) (quote [manor.tools.web :as web])) (web/start! (t/start-human-run) 8097) @(promise)'
// node test/manor/human_input_client_test.mjs http://127.0.0.1:8097
import {spawn} from 'node:child_process';
import {mkdtemp, readFile, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import assert from 'node:assert/strict';

const base = process.argv[2];
assert.match(base || '', /^http:\/\/(127\.0\.0\.1|localhost):809[0-9]$/);
const profile = await mkdtemp(join(tmpdir(), 'manor-human-browser-'));
const browser = spawn(process.env.CHROMIUM || 'chromium', ['--headless', '--no-sandbox', '--disable-gpu',
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
  ws = new WebSocket(targets.find(t => t.type === 'page').webSocketDebuggerUrl);
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
    throw new Error(`Timed out: ${expression}; errors=${JSON.stringify(errors)}; form=${await evaluate('document.getElementById("human-turn")?.textContent')}`);
  };
  const choose = tool => evaluate(`(() => {
    const form=document.getElementById('human-action'); form.elements.action.value=${JSON.stringify(tool)};
    form.elements.action.dispatchEvent(new Event('change',{bubbles:true}));
  })()`);
  const waitChanged = async previous => {
    await waitFor(`document.getElementById('human-turn')?.dataset.requestId !== ${JSON.stringify(previous)}`);
    await waitFor(`!!document.getElementById('human-action')`);
  };
  const submit = async () => {
    const previous = await evaluate(`document.getElementById('human-turn').dataset.requestId`);
    await evaluate(`document.getElementById('human-action').requestSubmit()`);
    await waitChanged(previous);
  };
  await cdp('Runtime.enable');
  await cdp('Page.navigate', {url: base + '/panel'});
  await waitFor(`!!document.getElementById('human-action') && typeof humanArgs === 'function'`);
  await choose('form-test');
  assert.equal(await evaluate(`document.querySelectorAll('[data-tool]:not([hidden])').length`), 1);
  assert.equal(await evaluate(`document.querySelector('[data-tool="form-test"] script')`), null, 'description escaped');
  assert.equal(await evaluate(`document.getElementById('human-action').checkValidity()`), false, 'required fields enforced');
  await evaluate(`(() => {
    const group=document.querySelector('[data-tool="form-test"]');
    const set=(arg,value)=>group.querySelector('[data-arg="'+arg+'"]').value=value;
    set('mode',JSON.stringify('b')); set('count','0'); set('hours','1.5'); set('flag','false');
    set('names','p1\\np2'); set('message','Draft text'); set('settings','{"level":"high"}');
  })()`);
  const expected = {mode: 'b', count: 0, hours: 1.5, flag: false, names: ['p1', 'p2'],
    'empty-lines': [], message: 'Draft text', settings: {level: 'high'}};
  assert.deepEqual(await evaluate(`humanArgs(document.getElementById('human-action'))`), expected);
  assert.equal(await evaluate(`document.getElementById('human-action').checkValidity()`), true);
  await choose('pass');
  assert.equal(await evaluate(`document.getElementById('human-action').checkValidity()`), true, 'inactive fields disabled');
  assert.deepEqual(await evaluate(`humanArgs(document.getElementById('human-action'))`), {});
  await choose('form-test');
  assert.deepEqual(await evaluate(`humanArgs(document.getElementById('human-action'))`), expected, 'switching tools keeps drafts');

  // Refresh through the real status listener, while keeping the same engine wait.
  await evaluate(`window.draftNode=document.querySelector('[data-tool="form-test"] [data-arg="message"]');
    draftNode.focus(); draftNode.setSelectionRange(2,5);
    es.dispatchEvent(new MessageEvent('status',{data:'x'}));`);
  await sleep(500);
  assert.equal(await evaluate(`document.activeElement===draftNode && draftNode.isConnected`), true, 'focus/node retained');
  assert.deepEqual(await evaluate(`[draftNode.selectionStart,draftNode.selectionEnd]`), [2, 5], 'caret retained');
  assert.deepEqual(await evaluate(`humanArgs(document.getElementById('human-action'))`), expected, 'live refresh keeps all drafts');
  await submit();
  await waitFor(`document.querySelector('.human-feedback')?.textContent.includes('Draft text')`);
  const feedback = await evaluate(`document.querySelector('.human-feedback').textContent.slice('Last result: '.length)`);
  assert.deepEqual(JSON.parse(feedback), expected, 'actual command decoded and executed');

  // Optional blanks are omitted, not coerced to false, zero or empty objects.
  await choose('form-test');
  await evaluate(`(() => {
    const group=document.querySelector('[data-tool="form-test"]');
    for(const [key,value] of Object.entries({mode:JSON.stringify('a'),count:'2',names:'p2',message:'Optional fields omitted'}))
      group.querySelector('[data-arg="'+key+'"]').value=value;
  })()`);
  assert.deepEqual(await evaluate(`humanArgs(document.getElementById('human-action'))`),
    {mode: 'a', count: 2, names: ['p2'], 'empty-lines': [], message: 'Optional fields omitted'});

  // Raw mode disables required structured fields, checks JSON locally, and
  // leaves schema/permission checks with the engine rather than bypassing them.
  await choose('note');
  await evaluate(`(() => {
    const group=document.querySelector('[data-tool="note"]');
    group.querySelector('details').open=true;
    group.querySelector('[data-raw-toggle]').click();
    group.querySelector('[data-raw]').value='broken json';
    document.getElementById('human-action').requestSubmit();
  })()`);
  await waitFor(`!!document.querySelector('.human-error').textContent`);
  assert.equal(await evaluate(`document.querySelector('[data-tool="note"] [data-fields]').disabled`), true);
  await evaluate(`document.querySelector('[data-tool="note"] [data-raw]').value='{}'`);
  await submit();
  await waitFor(`document.querySelector('.human-feedback')?.textContent.includes('invalid arguments')`);

  // Query result and free action both return to an updated human prompt via SSE.
  await choose('form-query'); await submit();
  await waitFor(`document.querySelector('.human-feedback')?.textContent.includes('queried')`);
  await choose('note');
  await evaluate(`document.querySelector('[data-tool="note"] [data-arg="text"]').value='Unlock another tool'`);
  await submit();
  await waitFor(`!!document.querySelector('#human-tool option[value="after-note"]')`);
  assert.equal(await evaluate(`!!document.querySelector('#human-tool option[value="before-note"]')`), false);

  // A stale browser form returns an HTTP error which is shown without clearing drafts.
  await choose('note');
  await evaluate(`document.querySelector('[data-tool="note"] [data-arg="text"]').value='Do not send';
    document.querySelector('[name="request-id"]').value='stale';
    document.getElementById('human-action').requestSubmit();`);
  await waitFor(`document.querySelector('.human-error').textContent.includes('turn has changed')`);
  assert.equal(await evaluate(`document.querySelector('[data-tool="note"] [data-arg="text"]').value`), 'Do not send');
  assert.equal(await evaluate(`document.querySelector('#human-action button').disabled`), false);
  await evaluate(`document.querySelector('[name="request-id"]').value=document.getElementById('human-turn').dataset.requestId`);
  await choose('pass');
  await evaluate(`document.getElementById('human-action').requestSubmit()`);
  await waitFor(`!document.getElementById('human-action') && document.getElementById('status').textContent.includes('last human action: done')`);
  assert.deepEqual(errors, [], 'no browser runtime exceptions');
  console.log('PASS human panel: schema fields, typing/zero/false/lists, optional omission, drafts/focus/caret, raw JSON/errors, free/query/final commands, refreshed availability, stale-form errors, live SSE.');
} finally {
  if (ws) ws.close();
  if (browser.exitCode === null) {
    const exited = new Promise(resolve => browser.once('exit', resolve));
    browser.kill('SIGTERM'); await exited;
  }
  await rm(profile, {recursive: true, force: true, maxRetries: 5, retryDelay: 100});
}
