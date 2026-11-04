import { run, S } from './lib.mjs';
// A1: new player opens 发现 › 俱乐部 entry, applies as 主理人 through all 4 steps
await run({ path: 'pages/club/apply/index', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(1200); await w.tap('开始申请'); await S(800);
  await w.ty('你的称呼', '林小舟'); await w.ty('手机号或微信号', '13800001111'); await w.ty('如 高校社团负责人 / 户外领队', '城市徒步领队');
  await w.shot('03-apply-step1-filled'); await w.tap('下一步'); await S(800); await w.say('step2'); await w.shot('04-apply-step2');
  try { await w.tap('5-20场'); } catch (e) { console.log('no exp chip', e.message); }
  await w.ty('如 50', '20'); await w.ty('如 20', '10');
  await w.tap('下一步'); await S(800); await w.say('step3'); await w.shot('05-apply-step3');
  await w.tap('能设计城市路线'); await w.tap('能与商家联动');
  await w.tap('下一步'); await S(800); await w.say('step4'); await w.shot('06-apply-step4');
  await w.tap('成为俱乐部主理人'); await S(2500); await w.say('done'); await w.shot('07-apply-done');
});
