/*
 * 阶段2 · 商家端只读走查（审查探针，不改业务代码）
 * 对 app.json 里 28 条 merchant 路由逐条 reLaunch，抓逻辑层状态 + 截图到 /tmp（要留证的再手工拷进 shots/）。
 * 写请求有 develop 版拦截闸，所以这里只走「进入 → 加载 → 空态」三步，回读/写操作留给人工或体验版。
 * 用法：node docs/audit-e2e-202609/walk-merchant.js
 */
const path = require('path');
const fs = require('fs');
const { openMiniProgram, closeMiniProgram } = require('../../tests/automator/harness.js');

const PROJECT_PATH = path.resolve(__dirname, '../..');
const OUT = '/tmp/chengyin-shots/walk';
// 放一个 walk-routes.txt（每行一条路由）就只走那份，用来单独复现某个页面
const OVERRIDE = path.join(__dirname, 'walk-routes.txt');

const ROUTES = [
  'pages/merchant/index/index',
  'pages/merchant/apply/index',
  'pages/merchant/ledger/index',
  'pages/merchant/ledger/order-detail/index',
  'pages/merchant/ledger/batch-detail/index',
  'pages/merchant/aftercare/index',
  'pages/merchant/aftercare/detail/index',
  'pages/merchant/predict/index',
  'pages/merchant/profile/index',
  'pages/merchant/marketing/index',
  'pages/merchant/marketing/ai-insight/index',
  'pages/merchant/reviews/index',
  'pages/merchant/relation/index',
  'pages/merchant/citynode/index',
  'pages/merchant/citynode/create/index',
  'pages/merchant/decor/index',
  'pages/merchant/decor/ai-npc/index',
  'pages/merchant/decor/coop-setting/index',
  'pages/merchant/decor/gallery/index',
  'pages/merchant/decor/perks/index',
  'pages/merchant/coop-center/index',
  'pages/merchant/customer/index',
  'pages/merchant/customer/detail/index',
  'pages/merchant/game-node/index',
  'pages/merchant/team/index',
  'pages/play/merchant/index',
  'pages/topic/merchantapply/index',
  'pages/topic/merchantinfo/merchantinfo',
];

const STATE_KEYS = /(state|loading|error|err|empty|permission|can[A-Z]|quota|count|total|show|is[A-Z]|need|ready|status)/;

function summarize(data) {
  const out = { keys: Object.keys(data || {}).length };
  for (const [k, v] of Object.entries(data || {})) {
    if (!STATE_KEYS.test(k)) continue;
    if (Array.isArray(v)) out[k] = `[${v.length}]`;
    else if (v && typeof v === 'object') out[k] = '{obj}';
    else out[k] = typeof v === 'string' ? v.slice(0, 80) : v;
  }
  return out;
}

async function main() {
  const onlyOverride = fs.existsSync(OVERRIDE);
  const routes = onlyOverride
    ? fs.readFileSync(OVERRIDE, 'utf8').split('\n').map((s) => s.trim()).filter(Boolean)
    : ROUTES;
  const jsonFile = onlyOverride ? 'walk-routes.json' : 'walk-merchant.json';
  fs.mkdirSync(OUT, { recursive: true });
  const session = await openMiniProgram({ projectPath: PROJECT_PATH });
  const mp = session.mp;
  const results = [];
  for (const route of routes) {
    const slug = route.replace(/^pages\//, '').replace(/\//g, '-');
    const rec = { route, ok: false };
    try {
      const page = await mp.reLaunch('/' + route);
      await page.waitFor(1500);
      rec.landedOn = page.path.replace(/^\//, '');
      rec.redirected = rec.landedOn !== route;
      // 回弹要用页面栈二次确认，不能只信 reLaunch 返回的那个 handle
      try {
        const stack = await mp.pageStack();
        const pages = await stack.pages();
        rec.stack = pages.map((p) => p.path.replace(/^\//, ''));
      } catch (e) { rec.stack = ['<pageStack 失败>']; }
      if (rec.redirected && rec.stack && rec.stack.length) rec.landedOn = rec.stack[rec.stack.length - 1];
      rec.redirected = rec.landedOn !== route;
      const data = await page.data();
      rec.state = summarize(data);
      await mp.screenshot({ path: path.join(OUT, slug + '.png') });
      rec.shot = slug + '.png';
      rec.ok = true;
    } catch (e) {
      rec.error = (e && e.message ? e.message : String(e)).slice(0, 200);
    }
    results.push(rec);
    console.log(`${rec.ok ? 'OK ' : 'ERR'} ${route}${rec.redirected ? ' → 落在 ' + rec.landedOn : ''}${rec.error ? ' :: ' + rec.error : ''}`);
  }
  fs.writeFileSync(path.join(OUT, jsonFile), JSON.stringify(results, null, 2));
  await closeMiniProgram(session);
  console.log(`\n明细:${path.join(OUT, jsonFile)}`);
}

main().catch((e) => {
  console.error('[walk-merchant] FATAL:', e && e.message ? e.message : e);
  throw e;
});
