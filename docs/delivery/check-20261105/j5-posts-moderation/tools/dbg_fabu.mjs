import { start, sleep } from './lib.mjs';
const w = await start({ path: `pages/publish/fabu/index?id=${process.env.TID}`, identity: 'e2e-openid-9003' });
const reqs = []; w.page.on('request', (r) => { if (/topic\/(v2\/)?update|publish-draft|edit-detail/.test(r.url())) reqs.push(r.url().replace(/^.*__api/, '') + ' ' + (r.postData() || '')); });
await sleep(4000); await w.tap('下一步', { exact: true }); await sleep(2500); await w.tap('预览', { exact: true }); await sleep(3000);
await w.page.mouse.click(255, 70); await sleep(5000);
for (const r of reqs) { const i = r.indexOf(' '); let j = null; try { j = JSON.parse(r.slice(i + 1)); } catch (e) {} console.log('REQ', r.slice(0, i), j ? JSON.stringify({ saveDraft: j.saveDraft, id: j.id, imgUrl: j.imgUrl, keys: Object.keys(j).join(',') }) : r.slice(i + 1, i + 200)); }
console.log('detail topic.imgUrl', JSON.stringify(await w.page.evaluate(async () => { const r = await fetch('/__api/api/topic/author-edit-detail?id=' + location.search, {method:'POST'}); return r.status; })));
await w.finish();
