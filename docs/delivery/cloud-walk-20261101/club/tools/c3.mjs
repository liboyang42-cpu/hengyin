import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(5000); await w.tap('去选一场'); await S(1500);
  await w.page.evaluate(() => getCurrentPages().slice(-1)[0].onSessionPick({ currentTarget: { dataset: { id: 18 } } }));
  await S(5000);
  await w.scroll(700); await S(600); await w.tap('修改', {exact:true}); await S(1500); await w.shot('76-lead-edit-time'); await txt(w,'edit');
  console.log('locked', JSON.stringify(await w.data('opsTimeLocked')));
  const p = await w.page.evaluate(() => { const pg = getCurrentPages().slice(-1)[0]; return Object.keys(pg).filter(k => /ops|Ops/.test(k)).join(','); }); console.log('opsfns', p);
  await w.page.evaluate(() => { const pg = getCurrentPages().slice(-1)[0]; (pg.onOpsTimeChange || pg.onOpsTime || function(){}).call(pg, { detail: { value: '14:05' } }); });
  await S(500); await w.tap('保存', {exact:true}); await S(2500); await w.shot('76b-lead-edit-time-save'); await txt(w,'saved');
  await w.tap('开始准备', {exact:true}); await S(2500); await w.shot('77-lead-start-prep'); await txt(w,'prep');
  await w.tap('开始准备', {exact:true, nth:0}); await S(500); await w.tap('开始准备', {exact:true, nth:1}).catch(()=>{});
  await S(3000); await w.shot('78-lead-preparing'); await txt(w,'preparing');
}
