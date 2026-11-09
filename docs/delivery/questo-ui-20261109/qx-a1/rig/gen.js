// node gen.js <xcxDir> <kind>  → prints previewData JSON for pages/play preview
const path = require('path');
const xcx = process.argv[2]; const kind = process.argv[3] || 'coinFlip';
const { buildPreviewKit } = require(path.join(xcx, 'pages/publish/utils/publish/advanced-game-preview.js'));
const imgs = ['/pages/play/images/d_topic1.png', '/pages/play/images/d_topic2.png', '/pages/play/images/d_topic4.png', '/pages/play/images/d_topic5.png'];
const CFG = {
  coinFlip: { coinFlip: { enabled: true, kicker: '抛一次，认结果', heads: { label: '正面', action: '向左走进弄堂' }, tails: { label: '反面', action: '沿街直走' } } },
  album: { album: { enabled: true, images: imgs.map((u, i) => ({ url: u, line: '第 ' + (i + 1) + ' 张' })) } },
  ballShake: { ballShake: { enabled: true, kicker: '撞够 30 次', goal: 30 } },
  qa: { qa: { enabled: true, mode: 'PICK', title: '这条街最常见的树是什么？', options: [{ id: 'a', label: '梧桐' }, { id: 'b', label: '银杏' }, { id: 'c', label: '香樟' }] } },
};
const cfg = CFG[kind] || CFG.coinFlip;
const para = (t) => ({ type: 'text', content: t });
const nodes = [
  { nodeId: 1, chapterId: 1, sortId: 1, name: '弄堂口', validationMethod: 0,
    advancedConfigJson: JSON.stringify(cfg), previewKit: buildPreviewKit(cfg, { title: '旧照片' }), done: false },
  { nodeId: 2, chapterId: 1, sortId: 2, name: '老邮局', validationMethod: 0, latitude: 31.2314, longitude: 121.4747, done: false },
];
const chapters = [{ id: 1, chapterId: 1, idxLabel: '第 1 章', name: '旧街', blocks: [
  para('雨停的时候，弄堂口的灯一盏一盏亮起来。'),
  para('你在门牌下停住，想起信里写的那句话：「去找那棵最老的树。」'),
  para('身后有人低声说，先别急着走，看看脚下。'),
  para('石板缝里压着一张褪色的车票，日期是十二年前。'),
  { type: 'node', nodeId: 1, nodeIndex: 0, locationRequired: false },
  para('（这一段在玩完之后才出现）'),
] }];
if (kind === 'route') {
  nodes.length = 0; chapters[0].blocks = chapters[0].blocks.filter(b => b.type === 'text').slice(0, 2);
  nodes.push({ nodeId: 11, chapterId: 1, sortId: 1, name: '武康大楼', address: '淮海中路 1850 号', validationMethod: 0, latitude: 31.2045, longitude: 121.4380, done: false },
             { nodeId: 12, chapterId: 1, sortId: 2, name: '老邮局', address: '北苏州路 250 号', validationMethod: 0, latitude: 31.2445, longitude: 121.4860, done: false });
  chapters[0].blocks.push({ type: 'node', nodeId: 11, nodeIndex: 0 }, { type: 'node', nodeId: 12, nodeIndex: 1 });
}
if (kind === 'tasks') {
  nodes.length = 0; delete chapters[0].blocks; chapters[0].description = '雨停的时候，弄堂口的灯一盏一盏亮起来。\n你在门牌下停住，想起信里写的那句话。';
  ['旧书摊', '修表铺', '弄堂里的猫'].forEach((nm, i) => nodes.push({ nodeId: 21 + i, chapterId: 1, sortId: 101 + i, name: nm, validationMethod: 0, done: false }));
}
process.stdout.write(JSON.stringify({ topicId: '', topicName: '旧街试玩', mode: 1, chapters, nodes, startStory: true, thoughts: [], endings: [], startState: [], look: '' }));
