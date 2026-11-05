// Hand walk: 成为主理人 (club/apply) then 创建俱乐部 as fixture player 9009, every step shot.
import { open, sleep } from './lib.mjs';
const w = await open({ path: 'pages/index/index', identity: 'e2e-openid-9009' });
const say = async (k) => console.log('##', k, '|', (await w.text()).slice(0, 600));
const fill = async (vals) => { const ins = await w.page.$$('pierce/input'); const tas = await w.page.$$('pierce/textarea'); const all = [...ins, ...tas]; let i = 0; for (const h of all) { const box = await h.boundingBox(); if (!box) continue; const v = await h.evaluate((e) => e.value); if (v) continue; if (i >= vals.length) break; await h.click(); await h.type(vals[i++], { delay: 5 }); } await sleep(400); };
const next = async () => { for (const t of ['下一步', '提交申请', '提交', '完成', '创建俱乐部']) if (await w.safeTap(t, { exact: true })) return t; return null; };
await w.open('/pages/club/apply/index'); await sleep(2500);
await w.tap('开始申请'); await sleep(1500);
await fill(['林测试', '13800000099']); await w.shot2('h04-apply-step1-filled');
console.log('next', await next()); await sleep(1500); await w.shot2('h05-apply-step2'); await say('step2');
await w.tap('1-5场'); await sleep(400); await w.shot2('h06-apply-step2-picked');
console.log('next', await next()); await sleep(1500); await w.shot2('h07-apply-step3'); await say('step3');
console.log('next-empty', await next()); await sleep(800); await say('step3 after empty next');
await w.shot2('h08-apply-step4');
await w.tap('资质 / 案例图'); await sleep(1500); await w.shot2('h09-apply-step4-photo'); await say('step4 photo');
await w.tap('成为俱乐部主理人'); await sleep(3000); await w.shot2('h10-apply-done'); await say('done');
console.log('route', (await w.route()).join('>'));
for (const t of ['创建俱乐部', '去创建', '创建']) if (await w.safeTap(t)) { console.log('tapped', t); break; }
await sleep(2500); console.log('route', (await w.route()).join('>')); await w.shot2('h11-create-open'); await say('create');
console.log(JSON.stringify(await w.report()));
await w.close();
