const { launch } = await import('/home/user/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs');
import { execFileSync } from 'node:child_process';
const A1 = '/home/user/staging/docs/delivery/questo-ui-20261109/qx-a1/rig';
const data = execFileSync('node', [A1 + '/gen.js', '/home/user/staging/chengyinhub-xcx', 'album']).toString();
const w = await launch({ path: 'pages/play/index?preview=1', identity: 'e2e-openid-9004', settle: 2500, agree: true });
const page = (await w.browser.pages()).slice(-1)[0];
const mode = process.argv[2] || 'reduce';
if (mode === 'reduce') await page.emulateMediaFeatures([{ name: 'prefers-reduced-motion', value: 'reduce' }]);
const cdp = await page.createCDPSession(); await cdp.send('Debugger.enable');
cdp.on('Debugger.paused', (ev) => { console.log('PAUSED'); ev.callFrames.slice(0, 12).forEach(f => console.log('  at', f.functionName || '(anon)', (f.url||'').replace(/^.*\//,''), f.location.lineNumber + 1)); process.exit(0); });
page.on('console', m => { if (/error/i.test(m.type())) console.log('CONSOLE', m.text().slice(0, 200)); });
await w.eval(`(() => { const p = getCurrentPages().slice(-1)[0]; p.applyPreviewData(${data}); })()`);
await new Promise(r => setTimeout(r, 2500));
const t = (s) => console.log(new Date().toISOString().slice(11, 19), s);
try { await w.tap('开始游戏'); } catch (e) { t('no start'); }
await new Promise(r => setTimeout(r, 2500));
try { await w.tap('轻点跳过'); } catch (e) {}
await new Promise(r => setTimeout(r, 2500));
for (let i = 0; i < 3; i++) {
  t('scroll ' + i); await w.scroll(600); await new Promise(r => setTimeout(r, 2500));
  t('eval');
  const st = await Promise.race([w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0];return {album:JSON.stringify(p.data.album||null).slice(0,160), paras:(p.data.chapterParas||[]).map(x=>x.kind).join(',')}})()`), new Promise(r => setTimeout(() => r('EVAL-TIMEOUT'), 8000))]);
  t(JSON.stringify(st));
  t('shot');
  const ok = await Promise.race([page.screenshot({ path: `/tmp/claude-0/a1shots/alb-${mode}-${i}.jpg`, type: 'jpeg', quality: 70 }).then(() => 'ok'), new Promise(r => setTimeout(() => r('SHOT-TIMEOUT'), 6000))]);
  t(ok); if (ok !== 'ok') { t('pausing'); cdp.send('Debugger.pause'); await new Promise(r => setTimeout(r, 15000)); t('no pause event'); break; }
}
process.exit(0);
