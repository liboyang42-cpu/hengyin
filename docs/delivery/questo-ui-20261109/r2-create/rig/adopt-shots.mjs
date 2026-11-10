// r2-create 采用流程取证(rig only):玩家 9004 打开模板 990015(qx-s1 save_as_template + rig_city_code 夹具)。
// 用法: RIG_SHOTS=<dir> node adopt-shots.mjs <before|after> [templateId]
const { launch } = await import((process.env.RIG_DIR || '/home/user/rig/docs/delivery/cloud-walk-20261101/rig') + '/mpweb/walk.mjs');
const tag = process.argv[2] || 'after';
const id = process.argv[3] || '990015';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const w = await launch({ path: 'pages/templatedetail/topic-detail/index?id=' + id, identity: 'e2e-openid-9004', agree: true });
await sleep(3500);
await w.tap('能力');
await sleep(1200);
await w.scroll(2000);
await sleep(800);
await w.shot(`U02-01-${tag}`);
await w.scroll(-4000);
await sleep(600);
await w.tap('应用', { exact: true });
await sleep(3500);
await w.shot(`U02-02-${tag}`);
if (tag === 'before') {
  // 改版前:弹「沿用原地点 / 换一个城市」,选沿用 → 确定
  await w.tap('沿用原地点'); await sleep(500); await w.tap('确定', { exact: true }); await sleep(3500);
}
console.log('route', JSON.stringify(await w.route()));
console.log('text', (await w.text()).slice(0, 600));
const net = await w.net();
console.log('net', JSON.stringify(net.filter((n) => /topic-template\/use|binding-status/.test(n.url || n.path || JSON.stringify(n))).slice(-4)));
if ((await w.route()).slice(-1)[0] === 'pages/publish/fabu/index') {
  await sleep(2500);
  try {
    await w.tap('地点待办'); await sleep(1500); await w.shot(`U02-03-${tag}`);
    if (tag === 'after') { await w.tap('确认地点'); await sleep(2500); await w.shot('U02-03-confirmed-after'); }
    console.log('after confirm', (await w.text()).slice(0, 400));
  } catch (e) { console.log('todos step', e.message); }
}
console.log('errors', JSON.stringify(w.errors()));
await w.browser.close();
