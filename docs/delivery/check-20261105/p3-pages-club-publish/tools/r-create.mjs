// Second pass: club/create F1 (one 下一步 tap while the name input still has focus) and F2 (guest).
// usage: node r-create.mjs guest | node r-create.mjs lead <openid> <clubName>
import { open, sleep } from './lib.mjs';
const mode = process.argv[2];
const say = async (w, k) => console.log('##', k, '|', (await w.text()).slice(0, 500));
if (mode === 'guest') {
  const w = await open({ path: 'pages/index/index' });
  await w.open('/pages/club/create/index'); await sleep(3000);
  await w.shot2('f2-01-guest-create'); await say(w, 'guest create');
  console.log('data', JSON.stringify(await w.data('bootstrapState')), JSON.stringify(await w.data('bootstrapError')));
  await sleep(6000); await w.shot2('f2-02-guest-create-9s');
  console.log('tap 登录', await w.safeTap('登录', { exact: true })); await sleep(2500);
  await w.shot2('f2-03-guest-create-login'); await say(w, 'after 登录');
  for (const t of ['同意', '允许', '确定', '微信一键登录', '一键登录']) if (await w.safeTap(t, { exact: true })) { console.log('tapped', t); await sleep(2500); }
  try { await w.modal('confirm'); } catch (e) {}
  await sleep(2500);
  console.log('route', (await w.route()).join('>'), 'state', JSON.stringify(await w.data('bootstrapState')));
  await w.shot2('f2-04-guest-create-after-login'); await say(w, 'after login');
  console.log(JSON.stringify(await w.report())); await w.close();
} else {
  const id = process.argv[3], name = process.argv[4] || '夜行者二测社';
  const w = await open({ path: 'pages/index/index', identity: id });
  await w.open('/pages/club/create/index'); await sleep(3000); await say(w, 'open');
  await w.tap('开始创建'); await sleep(1500);
  console.log('step', await w.data('step'));
  await w.tap('兴趣社群'); await sleep(400); await w.tap('下一步', { exact: true }); await sleep(1200);
  console.log('step', await w.data('step')); await say(w, 's2');
  await w.safeTap('城市定向'); await sleep(300); await w.tap('下一步', { exact: true }); await sleep(1200);
  console.log('step', await w.data('step')); await w.shot2('f1-01-step3'); await say(w, 's3');
  const inp = (await w.page.$$('pierce/input'))[0];
  await inp.click(); await inp.type(name, { delay: 30 }); await sleep(300);
  const focused = await w.page.evaluate(() => { let a = document.activeElement; while (a && a.shadowRoot && a.shadowRoot.activeElement) a = a.shadowRoot.activeElement; return a && a.tagName; });
  console.log('focused before tap', focused);
  await w.shot2('f1-02-step3-typed-focused');
  await w.tap('下一步', { exact: true }); await sleep(2500);
  const s = await w.data('step'); console.log('STEP AFTER ONE TAP', s);
  await w.shot2('f1-03-after-one-tap'); await say(w, 'after one tap');
  if (s === 4) {
    await w.safeTap('上海'); await sleep(500); await w.shot2('f1-04-step4-city');
    await w.tap('创建俱乐部', { exact: true }); await sleep(3500); await w.shot2('f1-05-created'); await say(w, 'created');
  }
  console.log(JSON.stringify(await w.report())); await w.close();
}
