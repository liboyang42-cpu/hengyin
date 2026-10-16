// 圈层主题候选表搬到主包 utils/circle-theme.js(BL-183):pages/topic 分包的项目首页也要读它,
// 分包之间不能互相 require。这里原路径保留转出,pages/play 现有引用不用改。
module.exports = require('../../../utils/circle-theme.js')
