// r2-create a3-v3-38/39 我的项目卡片取证(rig only)。主题 tab 用发布者 9003(rig 夹具主题 990014/990015);
// 模板 tab 用商家 9002(seed_my_templates.py 发布的 4 个节点玩法 + 原有一个)。
const { launch } = await import((process.env.RIG_DIR || '/home/user/rig/docs/delivery/cloud-walk-20261101/rig') + '/mpweb/walk.mjs');
const tag = process.argv[2] || 'after';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
{
  const w = await launch({ path: 'subpackageA/pages/myproject/index', identity: 'e2e-openid-9003', agree: true });
  await sleep(3500);
  await w.shot(`a3-v3-38-${tag}`);
  console.log('topics', (await w.text()).slice(0, 300));
  await w.browser.close();
}
{
  const w = await launch({ path: 'subpackageA/pages/myproject/index', identity: 'e2e-openid-9002', agree: true });
  await sleep(3000);
  await w.tap('模板', { exact: true });
  await sleep(2500);
  await w.shot(`a3-v3-39-${tag}`);
  console.log('templates', (await w.text()).slice(0, 300));
  console.log('errors', JSON.stringify(w.errors()));
  await w.browser.close();
}
