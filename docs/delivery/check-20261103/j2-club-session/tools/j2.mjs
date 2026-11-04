import { run, S } from './lib.mjs';
// J2: transfer: pick 4th row (990006, earliest joiner), 转让给所选成员 → 确认转让
await run({ path: 'pages/club/governance/index?clubId=990004', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3000); await w.tap('选择成员'); await S(1200);
  await w.tap('选择', { exact: true, nth: 3 }); await S(800);
  console.log('SEL', JSON.stringify(await w.data('selectedMemberId')));
  await w.tap('转让给所选成员'); await S(1500);
  await w.shot('144-transfer-confirm'); await w.tap('确认转让'); await S(1500); await w.shot('145-transfer-reason-required'); await w.ty('填写交接原因（必填）', '我下个月出国，交给林一帆继续带'); await w.tap('确认转让'); await S(3500); await w.say('done'); await w.shot('146-transfer-done');
  console.log('ROUTE2', JSON.stringify(await w.route()));
});
