// r2-create a5-v3-29 发布者实名对齐取证(rig only):玩家(未登记发布者实名)新建主题 → 票务设置 → 滚到底。
const { launch } = await import((process.env.RIG_DIR || '/home/user/rig/docs/delivery/cloud-walk-20261101/rig') + '/mpweb/walk.mjs');
const tag = process.argv[2] || 'after';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const w = await launch({ path: 'pages/publish/fabu/index', identity: 'e2e-openid-9001', agree: true });
await sleep(3000);
await w.tap('票务设置');
await sleep(1800);
// 票价改成 ¥10(收钱才要求发布者实名):走页面自己的同一份规则 syncIdentityReady
await w.eval(`(() => { const p = getCurrentPages().slice(-1)[0]; p.setData({ 'formData.tickets[0].price': 10 }); p.syncIdentityReady(); })()`);
await sleep(800);
await w.scroll(3000);
await sleep(900);
await w.shot(`a5-v3-29-${tag}`);
console.log('text', (await w.text()).slice(0, 500));
console.log('errors', JSON.stringify(w.errors()));
await w.browser.close();
