const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { gameTemplateCover } = require('../../utils/template-display.js');
const detail = require('../../pages/templatedetail/utils/templateDetail.js');
const discover = require('../../utils/discover-search.js');

const root = path.resolve(__dirname, '../..');
test('模板列表、搜索和玩法详情共用照片，旧封面不再占据画廊首位', () => {
  for (const title of ['今晚的暗号', '拍下这一刻', '今日搭配任务', '集合点亮', '街角谜题',
    '闭眼味觉师', '治愈音乐角', '午夜电台', '低碳行动', '今日城市签', '维修工具盲猜']) {
    const row = { id: 123, title, imgUrl: 'https://www.chengyinhub.com/prod-api/profile/template-covers/old.webp' };
    const cover = gameTemplateCover(row);
    assert.match(cover, /^https:\/\/www\.chengyinhub\.com\/prod-api\/profile\/template-photos-20261023\/.+\.webp$/);
    assert.ok(fs.existsSync(path.join(root, '../docs/assets/template-photos-20261023', path.basename(cover))));
    assert.equal(detail.normalizeTemplateDetail(row).coverUrl, cover);
    const found = discover.decorateItem('gameTemplate', row);
    assert.equal(found.cover, cover);
    assert.equal(found.art, '');
    assert.equal(detail.buildGallery({ ...row, imgUrls: [row.imgUrl] })[0], cover);
    assert.match(row.imgUrl, /old.webp$/, '不把打包路径写回源记录');
  }
});

test('主题模板保留名称和 ID，并使用照片；作者自传图片保留', () => {
  for (const name of ['风味巡游', '不惑之年', '预制人生', '高温末日逃生', '上海像一部旧电影']) {
    const row = { id: 990001, name, imgUrl: '' };
    assert.match(discover.decorateItem('topicTemplate', row).cover, /template-photos-20261023\/.+\.webp$/);
    assert.equal(row.imgUrl, '');
  }
  const custom = { title: '今晚的暗号', imgUrl: 'https://www.chengyinhub.com/author.jpg' };
  assert.equal(gameTemplateCover(custom), custom.imgUrl);
  assert.equal(detail.normalizeTemplateDetail(custom).coverUrl, custom.imgUrl);
  assert.equal(gameTemplateCover({ title: '未知作者的新模板' }), '');
  for (const title of ['toString', 'constructor', '__proto__']) assert.equal(gameTemplateCover({ title }), '');
});

test('十二张摄影素材来源可追溯；保存在仓库交付目录，不进入小程序主包', () => {
  const dir = path.join(root, '../docs/assets/template-photos-20261023');
  const files = fs.readdirSync(dir).filter(name => name.endsWith('.webp'));
  assert.equal(files.length, 12);
  let bytes = 0;
  for (const file of files) {
    const data = fs.readFileSync(path.join(dir, file));
    assert.equal(data.toString('ascii', 0, 4), 'RIFF');
    assert.equal(data.toString('ascii', 8, 12), 'WEBP');
    bytes += data.length;
  }
  assert.ok(bytes < 150 * 1024, `${bytes} bytes`);
});
