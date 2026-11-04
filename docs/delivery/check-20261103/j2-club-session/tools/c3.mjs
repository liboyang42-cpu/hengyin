import { run, S } from './lib.mjs';
export async function openEditor(w) {
  await S(2500); await w.tap('管理', { exact: true }); await S(1500);
  await w.tap('发布主题', { nth: 2 }); await S(1200);
  await w.eval(`window.MPWEB.locQueue = [
    {name:'人民广场音乐喷泉', address:'上海市黄浦区人民大道', lat:31.2317, lng:121.4752},
    {name:'上海博物馆', address:'上海市黄浦区人民大道201号', lat:31.2284, lng:121.4755},
    {name:'大世界', address:'上海市黄浦区西藏南路1号', lat:31.2297, lng:121.4810}]`);
  await w.tap('城市定向 · 顺序探索'); await S(5000);
}
if (process.argv[1].endsWith('c3.mjs')) await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2-lead-a', agree: true }, async (w) => {
  await openEditor(w);
  await w.tap('主题详情'); await S(1500); await w.say('detail'); await w.shot('31-editor-theme-detail');
  await w.scroll(700); await w.say('detail2'); await w.shot('31b-editor-theme-detail-2');
  await w.scroll(700); await w.say('detail3');
});
