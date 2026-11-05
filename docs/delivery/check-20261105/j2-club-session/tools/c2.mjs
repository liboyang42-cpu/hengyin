import { run, S } from './lib.mjs';
// C2: editor's 主题详情 and 票务设置 sheets: is 商家承接 / 招商截止 offered for club city orienteering?
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2b-lead', agree: true, lat: 31.2304, lng: 121.4737 }, async (w) => {
  await S(2500); await w.tap('管理', { exact: true }); await S(1500);
  await w.tap('发布主题', { nth: 1 }); await S(1500);
  await w.eval(`window.MPWEB.locQueue = []`);
  await w.tap('城市定向 · 顺序探索'); await S(5000);
  await w.tap('主题详情'); await S(1500); await w.say('detail'); await w.shot('21-editor-theme-detail');
  for (let i = 0; i < 4; i++) { await w.scroll(600); await S(400); await w.say('detail' + i); await w.shot('22-editor-theme-detail-' + i); }
  console.log('FORM', JSON.stringify(await w.eval(`(function(){var d=getCurrentPages().slice(-1)[0].data;return {openMerchantPool:d.formData.openMerchantPool,recruitDeadline:d.formData.recruitDeadline,clubId:d.formData.clubId,selfPlay:d.formData.selfPlay}})()`)));
});
