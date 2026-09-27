// Drives the microcode page in a headless Chrome over the DevTools protocol: links, buttons,
// combos and the search box, checking what the page says after each. Run by
// tools/web-smoke.sh against a running pdp11-web; needs Node 22 (for its WebSocket) and Chrome.
const port = Number(process.env.CDP_PORT || 9333);
const base = process.env.WEB_URL || 'http://localhost:8080';
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function target() {
  for (let i = 0; i < 50; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${port}/json/new?about:blank`, { method: 'PUT' });
      return await r.json();
    } catch { await sleep(200); }
  }
  throw new Error('no chrome');
}
const t = await target();
const ws = new WebSocket(t.webSocketDebuggerUrl);
await new Promise(r => ws.onopen = r);
let id = 0; const pending = new Map();
ws.onmessage = m => { const d = JSON.parse(m.data); if (d.id && pending.has(d.id)) { pending.get(d.id)(d); pending.delete(d.id); } };
const send = (method, params = {}) => new Promise(r => { const i = ++id; pending.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
const ev = async expr => { const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true }); if (r.result.exceptionDetails) throw new Error(JSON.stringify(r.result.exceptionDetails)); return r.result.result.value; };
const status = () => ev(`document.querySelector('.pdp-status')?.textContent`);
const message = () => ev(`document.querySelector('.pdp-message')?.textContent`);
const clickText = async (sel, text) => { const ok = await ev(`(() => { const e = [...document.querySelectorAll('${sel}')].find(x => x.textContent.trim() === ${JSON.stringify(text)}); if (!e) return false; e.click(); return true; })()`); if (!ok) throw new Error('not found: ' + text); await sleep(1200); };
const setSelect = async (css, label) => { const ok = await ev(`(() => { const s = document.querySelector('.${css} select, select.${css}'); const o = [...s.options].find(o => o.text === ${JSON.stringify(label)}); if (!o) return false; s.value = o.value; s.dispatchEvent(new Event('change', {bubbles: true})); return true; })()`); if (!ok) throw new Error('no option ' + label); await sleep(1500); };
const typeSearch = async text => { await ev(`(() => { const i = document.querySelector('.pdp-search input'); i.focus(); i.value = ${JSON.stringify(text)}; i.dispatchEvent(new Event('input', {bubbles: true})); })()`); };
let failures = 0;
const check = (what, cond, got) => { console.log((cond ? 'PASS ' : 'FAIL ') + what + (cond ? '' : '  -> ' + got)); if (!cond) failures++; };

await send('Page.enable');
await send('Page.navigate', { url: base + '/to.etc.pdp11.web.MicrocodePage.ui?source=PDP1144&upc=0043' });
await sleep(3000);
let s = await status();
check('opens on 0043', s?.startsWith('µPC = 0043'), s);

await clickText('.pdp-mw .ui-lbtn', '2-J (0732)');
s = await status(); check('predecessor link goes to 0732', s?.startsWith('µPC = 0732'), s);

await clickText('button', 'Back');
s = await status(); check('Back returns to 0043', s?.startsWith('µPC = 0043'), s);

await clickText('button', 'Next instruction');
s = await status(); check('Next follows the fall-through', s && !s.startsWith('µPC = 0043') && s.startsWith('µPC = '), s);

await setSelect('pdp-source', 'PDP-11/05 (M7261 rev F)');
s = await status();
check('choosing rev F shows the 1976 drawing set', s?.includes('kd11b-microcode-1976'), s);
const byOptions = await ev(`[...document.querySelector('.pdp-searchby select, select.pdp-searchby').options].map(o => o.text).filter(t => t).join(',')`);
check('rev F cannot be searched by listing line', !byOptions.includes('Listing line'), byOptions);

await setSelect('pdp-searchby', 'Symbolic tag');
await typeSearch('U1-1');
await clickText('button', 'Go');
s = await status(); check('tag search finds U1-1', s?.includes('U1-1'), s);
const differs = await ev(`[...document.querySelectorAll('tr.pdp-differs td:first-child')].map(t => t.textContent.trim()).join('|')`);
check('rev E/F disagreement marked on AUX and CKO', /^AUX.*\|CKO/.test(differs), differs);

await setSelect('pdp-searchby', 'µPC');
await typeSearch('377');
await clickText('button', 'Go');
const m = await message(); s = await status();
check('an unprinted µPC says so', m?.includes('does not print'), m);
check('and nothing moved', s?.includes('U1-1'), s);

await setSelect('pdp-searchby', 'Symbolic tag');
await typeSearch('B-1');
await send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, text: '\r' });
await send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 });
await sleep(1500);
s = await status(); check('Enter in the search box searches', s?.includes('B-1'), s);

ws.close();
process.exit(failures ? 1 : 0);
