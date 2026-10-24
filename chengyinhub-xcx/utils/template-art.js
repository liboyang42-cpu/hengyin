// 玩法模板的具象图标:与模板页 pages/template/index.js 的 INTERACTION_ICON_BY_TITLE 逐条一致
// (模板页那份带 UI 基线行号锚点暂不动;两边改一处要同步改另一处,template-art 单测会比对)。
// 搜索结果里的玩法模板用这里取图,和模板页长得一样,不再用通用链接图标占位。
const { gameTemplateCover } = require('./template-display.js');

const INTERACTION_ICON_BY_TITLE = {
  '今晚的暗号': '/images/interaction-templates/merchant-secret-code.svg',
  '老板的秘密题': '/images/interaction-templates/merchant-secret-question.svg',
  '拍下这一刻': '/images/interaction-templates/merchant-photo-moment.svg',
  '收集这枚风味印章': '/images/interaction-templates/merchant-flavor-stamp.svg',
  '新品盲测局': '/images/interaction-templates/merchant-blind-test.svg',
  '今日搭配任务': '/images/interaction-templates/merchant-style-mission.svg',
  '集合点亮': '/images/interaction-templates/club-gather.svg',
  '小队竞速点亮': '/images/interaction-templates/club-team-race.svg',
  '街角谜题': '/images/interaction-templates/club-street-puzzle.svg',
  '城市取景任务': '/images/interaction-templates/club-city-camera.svg',
  '城市接力棒': '/images/interaction-templates/club-relay.svg',
  '今日角色任务': '/images/interaction-templates/club-role-mission.svg',
  // 生活备份 G0–G6(cms_template_library,migration_seed_life_backup_games_20260820)
  'G0｜30秒生活掉线测试': '/images/interaction-templates/life-outage-test.svg',
  'G1｜现在出状况,你先选什么': '/images/interaction-templates/life-what-first.svg',
  'G2｜我的底线三选一': '/images/interaction-templates/life-bottom-line.svg',
  'G3｜营业时间拼图': '/images/interaction-templates/life-hours-puzzle.svg',
  'G4｜去之前准备什么': '/images/interaction-templates/life-prep-checklist.svg',
  'G5｜值不值得现在处理': '/images/interaction-templates/life-worth-now.svg',
  'G6｜首选失效怎么办': '/images/interaction-templates/life-first-choice-offline.svg',
};

/** 模板页同一套取图:已登记标题用具象图标;作者没传封面时按玩法机制补图;都没有就空(走封面或兜底)。 */
function templateArt(item) {
  const source = item || {};
  return INTERACTION_ICON_BY_TITLE[source.title || ''] || (!source.imgUrl ? gameTemplateCover(source) : '');
}

module.exports = { INTERACTION_ICON_BY_TITLE, templateArt };
