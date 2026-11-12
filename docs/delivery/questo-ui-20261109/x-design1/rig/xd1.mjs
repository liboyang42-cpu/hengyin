import fs from 'node:fs';
const [port, tag, outDir, specFile, only] = process.argv.slice(2);
process.env.RIG_WEB = `http://127.0.0.1:${port}`;
const { launch } = await import('./walk.mjs');
const spec = JSON.parse(fs.readFileSync(specFile, 'utf8'));
fs.mkdirSync(outDir, { recursive: true });
for (const s of spec) {
  if (only && s.id !== only) continue;
  let w;
  try {
    w = await launch({ path: s.path, settle: 2500, agree: true, identity: s.identity || 'member-1' });
    await w.page.evaluate((d) => { const p = getCurrentPages().slice(-1)[0]; p.setData(d); }, s.data || {});
    await new Promise((r) => setTimeout(r, s.wait || 1200));
    if (s.after) await w.page.evaluate(s.after);
    await new Promise((r) => setTimeout(r, 500));
    await w.page.screenshot({ path: `${outDir}/${s.id}-${tag}.jpg`, type: 'jpeg', quality: 70 });
    console.log('ok', s.id, JSON.stringify(w.errors().slice(0, 1)).slice(0, 200));
  } catch (e) { console.log('FAIL', s.id, String(e).slice(0, 200)); }
  if (w) await w.browser.close();
}
