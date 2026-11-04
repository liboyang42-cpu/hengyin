import { run, S } from './lib.mjs';
// J1: lead → 设置 → 成员治理 → pick member → 转让主理人
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(2500); await w.tapSel('.cover-nav-act'); await S(1500);
  await w.tap('成员治理'); await S(3000); console.log('ROUTE1', JSON.stringify(await w.route()));
  await w.say('gov'); await w.shot('140-governance');
  await w.scroll(600); await w.say('gov2'); await w.shot('141-governance-2');
});
await run({ path: 'pages/club/governance/index?clubId=990004', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3000); await w.tap('选择成员'); await S(1200); await w.say('picker'); await w.shot('142-governance-member-picker');
  const d = await w.data('members'); console.log('MEMBERS', JSON.stringify((d || []).map(m => [m.memberId, m.nickname])));
  const idx = (d || []).findIndex(m => String(m.memberId) === '990006'); console.log('IDX', idx);
  await w.eval(`getCurrentPages().slice(-1)[0].pickMember({ currentTarget: { dataset: { memberId: 990006 } } })`).catch(() => {});
  await S(800); await w.say('picked'); await w.shot('143-governance-picked');
  await w.tap('转让给所选成员'); await S(1500); await w.say('confirm'); await w.shot('144-transfer-confirm');
});
