import { run, S } from './lib.mjs';
// C1: lead's 管理 tab before any theme; 发布主题 › 城市定向; pick 3 stations in the continuous picker, then cancel back to the editor
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2b-lead', agree: true, lat: 31.2304, lng: 121.4737 }, async (w) => {
  await S(2500); await w.shot('14-club-home-lead');
  await w.tap('管理', { exact: true }); await S(1500); await w.say('manage'); await w.shot('15-club-manage-empty');
  const t = await w.text(); const n = (t.match(/发布主题/g) || []).length; console.log('PUBLISH_COUNT', n);
  await w.tap('发布主题', { nth: 1 }); await S(1500); await w.say('choice'); await w.shot('16-publish-choice');
  await w.eval(`window.MPWEB.locQueue = [
    {name:'人民广场音乐喷泉', address:'上海市黄浦区人民大道', lat:31.2317, lng:121.4752},
    {name:'上海博物馆', address:'上海市黄浦区人民大道201号', lat:31.2284, lng:121.4755},
    {name:'大世界', address:'上海市黄浦区西藏南路1号', lat:31.2297, lng:121.4810}]`);
  await w.tap('城市定向', { nth: 0 }); await S(5000); await w.say('editor'); await w.shot('17-editor-after-picks');
  await w.scroll(700); await w.say('editor2'); await w.shot('18-editor-2');
  await w.scroll(700); await w.say('editor3'); await w.shot('19-editor-3');
  await w.scroll(900); await w.say('editor4'); await w.shot('20-editor-4');
});
