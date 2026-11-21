const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..', '..');
const read = (rel) => fs.readFileSync(path.join(root, rel), 'utf8');

// 发布收尾 Lane G2:4 个已合文案组源码在 master 上但没有锁定文案的专项测试(回归缺口)。
// 这里按当前文案原样锁住;改文案须同步改本测试,并确认语义(不承诺/不混用)仍成立。
const GROUPS = {
  // 2026-11-19 PG-070(业主 2026-11-17 批准「删说明小字」):tutorial-version-copy 三处说明已撤,改由下方反向断言锁定。
  'group-code-copy': [
    ['pages/club/group-code/index.wxml', 'title="活动核验团码"'],
    // 第十轮 f3 第 5 项(j2 F-24):入队页这句说明按 owner「删说明小字」规则撤掉(旧锁定是被取代的旧决定);
    // 第十二轮 k3(p3 N11):团码页「团码仅限本场次；组队邀请码不能核销」同按此规则撤掉 —— 码只在选定场次后
    // 才出现,组队邀请码本身也不会被服务端当团码核销(ApiVerifyGroupCode),语义由页面结构与服务端承担。
  ],
  // 'topic-comment-scope':2026-11-21 新玩家走查(N2)按 owner「删说明小字」把评价下面那行积分口径小字也撤了,
  // 由文末反向断言锁定;积分规则仍可从成长中心进入。
};

for (const [group, entries] of Object.entries(GROUPS)) {
  test(`已合文案组 ${group}:当前文案原样在位`, () => {
    for (const [rel, copy] of entries) {
      assert.ok(read(rel).includes(copy), `${rel} 缺少 ${group} 文案:${copy}`);
    }
  });
}

test('PG-070:教程版本说明三处已撤,不许回潮', () => {
  for (const f of ['subpackageA/components/scene-how-to-play-detail/index.wxml', 'subpackageA/pages/infomation/infomation.wxml', 'subpackageA/pages/infomationdetail/infomationdetail.wxml']) {
    const src = fs.readFileSync(path.join(root, f), "utf8")
    assert.doesNotMatch(src, /适用玩法版本暂未标注|实际玩法以游玩页规则为准/, f)
  }
})

test('N2(2026-11-21):路线详情评价下的积分口径小字已撤,不许回潮', () => {
  const src = fs.readFileSync(path.join(root, 'pages/topic/index/index.wxml'), 'utf8')
  assert.doesNotMatch(src, /评论积分须符合参与及首评条件/)
})
