// r2-kits 截图驱动:打开真实 pages/play/index,把服务端形状的会话视图喂给本树自己的 pickPlayKit,
// 由真实 cy-playkit 组件渲染。同一份视图在改前/改后两个包上各跑一次。
// 用法: RIG_SHOTS=<dir> SUFFIX=before|after node kit-shots.mjs [state...]
import { launch } from '/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs';
import { STATES } from './kit-states.mjs';

const suffix = process.env.SUFFIX || 'after';
const only = process.argv.slice(2);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const errs = [];
for (const s of STATES) {
  if (only.length && !only.includes(s.id)) continue;
  /* 每个状态重开一次页面:上一个状态的系统弹窗 / 确认层不会带进来 */
  const w = await launch({ path: 'pages/play/index', identity: 'e2e-openid-9004', agree: true, settle: 3500 });
  await w.eval(`(() => {
    const pv = MPWEB.require('pages/play/utils/playkit-view.js');
    const view = ${JSON.stringify(s.view)};
    const kit = pv.pickPlayKit(view, Date.now());
    const p = getCurrentPages().slice(-1)[0];
    p.setData({ playKit: Object.assign({}, p.data.playKit || {}, { show: false, kit: null }) });
    setTimeout(() => p.setData({ playKit: Object.assign({}, p.data.playKit || {}, { show: true, kit }) }), 50);
  })()`);
  await sleep(s.wait || 2600);
  for (const step of s.steps || []) {
    if (step.tapSel) await w.tapSel(step.tapSel);
    if (step.tap) await w.tap(step.tap);
    if (step.tapAny) { for (const t of step.tapAny) { try { await w.tap(t); break; } catch (e) { /* 改前/改后按钮文案不同 */ } } }
    if (step.eval) await w.eval(step.eval);
    await sleep(step.wait || 700);
  }
  console.log('shot', s.id, await w.shot(`${s.id}-${suffix}`));
  errs.push(...w.errors());
  await w.browser.close();
}
if (errs.length) console.log('PAGE ERRORS', errs.slice(0, 5));
