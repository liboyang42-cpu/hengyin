import { run, S } from './lib.mjs';
// B3: lead opens 设置 → 角色与权限, assigns a role to a member
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(2500); await w.tapSel('.cover-nav-act'); await S(1500); await w.shot('22-club-settings');
  await w.tap('角色与权限'); await S(2500); await w.shot('23-roles');
  await w.tap('选择成员'); await S(800); await w.shot('24-roles-member-picker');
  await w.tap('选择', { exact: true, nth: 3 }); await S(800);  // 4th row = earliest joiner (no way to tell rows apart)
  await w.tap('角色', { exact: true }); await S(800); await w.say('rolepicker'); await w.shot('25-roles-role-picker');
  await w.tap('选择', { exact: true, nth: 1 }); await S(800); await w.say('chosen'); await w.shot('26-roles-ready');
  await w.tap('分配', { exact: true }); await S(1500); await w.say('assigned'); await w.shot('27-roles-assigned');
});
