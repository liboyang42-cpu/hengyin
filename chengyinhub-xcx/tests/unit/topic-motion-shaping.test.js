/* 2026-10-22 主题详情动效落地的数据整形(pages/topic/utils/topic-motion.js)。
   钉住的事实:只用后端已下发的字段,缺了就空,不编造;期限按「购买后 90 天 / 主题结束」取早。 */
const test = require('node:test')
const assert = require('node:assert/strict')
const m = require('../../pages/topic/utils/topic-motion.js')

test('封面轮换图:多图优先,其次单张封面,都没有就空', () => {
  assert.deepEqual(m.buildCoverImages({ imgArr: 'a.jpg, b.jpg,,c.jpg', imgUrl: 'x.jpg' }), ['a.jpg', 'b.jpg', 'c.jpg'])
  // 服务端 AppMediaReferenceGuard 把多图签名地址用「;」拼回(requireAttachments),客户端两种分隔都要认
  assert.deepEqual(m.buildCoverImages({ imgArr: 'https://o/upload/a.jpg?Signature=x%3D;https://o/upload/b.jpg?Signature=y' }),
    ['https://o/upload/a.jpg?Signature=x%3D', 'https://o/upload/b.jpg?Signature=y'])
  assert.deepEqual(m.buildCoverImages({ imgUrl: 'x.jpg' }), ['x.jpg'])
  assert.deepEqual(m.buildCoverImages({}), [])
})

test('发布者:公开主体名优先,个人发布才用头像;都没有就不出', () => {
  assert.deepEqual(m.buildPublisher({ contentPublisherName: '夜跑俱乐部', contentPublisherType: 'CLUB', clubName: '别的' }), { name: '夜跑俱乐部', avatar: '' })
  assert.deepEqual(m.buildPublisher({ contentPublisherName: '小李', contentPublisherType: 'MEMBER', umsMember: { avatar: 'a.png' } }), { name: '小李', avatar: 'a.png' })
  assert.deepEqual(m.buildPublisher({ clubName: '承接社' }), { name: '承接社', avatar: '' })
  assert.deepEqual(m.buildPublisher({}), { name: '', avatar: '' })
})

test('头图主按钮:已购去票夹、未开售写原因(点了讲为什么)、打烊不出、自玩写价格', () => {
  assert.equal(m.buildHeroCta({ selfPlay: 1 }, { isJoin: 1 }).kind, 'joined')
  assert.deepEqual(m.buildHeroCta({ lifecycle: 1, selfPlay: 1 }, {}), { kind: 'locked', text: '招募中 · 未开售' })
  assert.equal(m.buildHeroCta({ lifecycle: 7 }, {}).text, '立项待审 · 未开售')
  assert.equal(m.buildHeroCta({ selfPlay: 1 }, { merchantClosed: true }).text, '')
  assert.deepEqual(m.buildHeroCta({ selfPlay: 1 }, { priceText: '¥68' }), { kind: 'selfplay', text: '¥68 随时开玩' })
  assert.equal(m.buildHeroCta({}, {}).kind, 'signup')
})

test('参与商家卡按商家去重,取封面/一句话介绍/权益', () => {
  const shop = { id: 9, name: '旧城咖啡', coverImage: 'c.jpg', slogan: '手冲', derivativeBenefits: '送饼干' }
  const chapters = [{ nodes: [{ name: '一站', registrationMerchantList: [{ memberId: 7, mmsMerchant: shop }] }] },
    { nodes: [{ name: '二站', registrationMerchantList: [{ memberId: 7, mmsMerchant: shop }, { mmsMerchant: {} }] }] }]
  const cards = m.buildMerchantCards(chapters)
  assert.equal(cards.length, 1)
  assert.deepEqual(cards[0], { key: 'm-9', targetId: 7, name: '旧城咖啡', img: 'c.jpg', desc: '手冲', gift: '送饼干', place: '一站' })
})

test('故事卡:城市定向一章一张(末章叫终章),自由探索用商家', () => {
  const info = { chaptersList: [{ name: '序' , description: 'a' }, { name: '中' }, { name: '末' }, { name: '' }] }
  assert.deepEqual(m.buildStoryCards(info).map((c) => c.label), ['第一章', '第二章', '第三章'])
  const four = { chaptersList: [{ name: '1' }, { name: '2' }, { name: '3' }] }
  assert.equal(m.buildStoryCards(four)[2].label, '终章')
  assert.equal(m.buildStoryCards({ productType: 2 }, [{ name: '店', img: 'i', desc: 'd', place: 'p' }])[0].label, '第1页')
})

test('可玩期限:有 expiresAt 用它;否则购买后 90 天与主题结束日取早', () => {
  const now = new Date(2026, 9, 22, 10, 0, 0)
  const far = m.passWindow({ now, topicEnd: '2027-06-30' })
  assert.equal(far.daysText, '90')
  assert.equal(far.untilText, '01/20')
  const near = m.passWindow({ now, topicEnd: '2026-11-01' })
  assert.equal(near.untilText, '11/01')
  assert.match(near.line, /主题结束/)
  assert.equal(m.passWindow({ now, expiresAt: '2026-10-30 23:59:59' }).untilText, '10/30')
})

test('组队块只算本主题的队,还差人数 = 上限 − 已加入', () => {
  const rows = [
    { teamId: 1, topicId: 5, title: 'A 队', joinedCount: 2, maxMembers: 4, memberAvatarsJoined: 'a.png,b.png' },
    { teamId: 2, topicId: 5, joinedCount: 3, maxMembers: 3 },
    { teamId: 3, topicId: 6, joinedCount: 1, maxMembers: 4 },
  ]
  const t = m.buildTeams(rows, 5)
  assert.equal(t.rows.length, 2)
  assert.equal(t.need, 2)
  assert.deepEqual(t.rows[0].faces, ['a.png', 'b.png'])
  assert.equal(t.rows[1].title, '主题队伍')
  assert.deepEqual(m.buildTeams(null, 5), { rows: [], need: 0 })
})
