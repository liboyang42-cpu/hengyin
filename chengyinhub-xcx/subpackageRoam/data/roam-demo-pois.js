// 漫游演示种子点(PG-061):只在开发版 + features.roamDemoPois 显式打开时由 pages/roam 异步加载;
// 放在 subpackageRoam 分包里,不打进主包。点位是相对玩家当前位置的偏移(米),由调用方的 P(x, y) 换算经纬度。
function demoPois(P, MOCK_IMG) {
  return [
    { id: 108, cat: 'merchant', name: '转角奶茶', ...P(35, -40), points: 20, desc: '放学路上总要绕的那家。', imgUrl: MOCK_IMG },
    { id: 109, cat: 'merchant', name: '巷子面馆', ...P(-50, 25), points: 25, desc: '老板凌晨四点就开始熬汤。', imgUrl: MOCK_IMG },
    { id: 110, cat: 'merchant', name: '拐角披萨', ...P(20, 55), points: 30, desc: '一整块芝士拉到看不见边。', imgUrl: MOCK_IMG },
    { id: 101, cat: 'merchant', name: '深夜食堂', ...P(-40, -120), points: 30, desc: '凌晨两点还亮着灯的那家。', imgUrl: MOCK_IMG },
    { id: 111, cat: 'merchant', name: '老友烧烤', ...P(-70, -95), points: 20, desc: '啤酒论箱起卖，老板不劝酒也不劝退。', imgUrl: MOCK_IMG },
    { id: 102, cat: 'park', name: '苏堤小游园', ...P(-190, 20), points: 20, desc: '傍晚有人在这里遛狗、下棋、发呆。' },
    { id: 103, cat: 'landmark', name: '城西美术馆', ...P(-320, 230), points: 25, desc: '免费开放的那层，总有学生在临摹。' },
    { id: 104, cat: 'park', name: '梧桐口袋公园', ...P(-130, 390), points: 20, desc: '一小片被高楼夹住的绿。' },
    { id: 105, cat: 'merchant', name: '山丘咖啡', ...P(130, 480), points: 30, desc: '巷口第三家。老板记得每个熟客的豆子。', imgUrl: MOCK_IMG },
    { id: 112, cat: 'merchant', name: '巷角包子', ...P(160, 460), points: 20, desc: '皮薄馅大，早上六点就排队。', imgUrl: MOCK_IMG },
    { id: 106, cat: 'landmark', name: '老钟楼', ...P(290, 310), points: 25, desc: '整点还会响。' },
    { id: 107, cat: 'merchant', name: '无早旧书店', ...P(150, 120), points: 30, desc: '二手书按气味分类的地方。', imgUrl: MOCK_IMG },
    { id: 113, cat: 'merchant', name: '老巷炸串', ...P(120, 145), points: 20, desc: '油锅从下午响到凌晨。', imgUrl: MOCK_IMG },
  ];
}

module.exports = { demoPois };
