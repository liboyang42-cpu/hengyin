import { run, S } from './lib.mjs';
// A3: lead adds club logo and cover from 编辑资料 (real upload path; WeChat image check answered by the rig seam)
await run({ path: 'pages/club/edit/index?id=990004', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(2500); await w.shot('31-club-edit');
  await w.tap('封面', { exact: true }); await S(2000); try { await w.tap('选取'); } catch (e) { console.log(e.message); } await S(4000);
  await w.tap('头像', { exact: true }); await S(2000); try { await w.tap('选取'); } catch (e) { console.log(e.message); } await S(4000);
  await w.say('uploaded'); await w.shot('32-club-edit-uploaded');
  await w.tap('保存修改'); await S(2500); await w.say('saved'); await w.shot('33-club-edit-saved');
});
