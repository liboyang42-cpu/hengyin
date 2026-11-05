import { run, S } from './lib.mjs';
// L1: lead → 设置 → 成员治理 → 选择成员 → 林一帆 → 转让给所选成员 → reason → 确认转让
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(2500); await w.tapSel('.cover-nav-act'); await S(1500);
  await w.tap('成员治理'); await S(3000); console.log('ROUTE1', JSON.stringify(await w.route()));
  await w.say('gov'); await w.shot('150-governance');
  await w.tap('选择成员'); await S(1500); await w.say('picker'); await w.shot('151-governance-member-picker');
  await w.tap('林一帆'); await S(800); await w.say('picked'); await w.shot('152-governance-picked');
  await w.tap('转让给所选成员'); await S(1500); await w.say('confirm'); await w.shot('153-transfer-confirm');
  await w.tap('确认转让'); await S(1500); await w.say('reason-req'); await w.shot('154-transfer-reason-required');
  await w.ty('填写交接原因（必填）', '我下个月出国，交给林一帆继续带'); await w.tap('确认转让'); await S(3500);
  await w.say('done'); await w.shot('155-transfer-done'); console.log('ROUTE2', JSON.stringify(await w.route()));
});
