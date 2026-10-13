/**
 * 8-07 阻断#20-B/C 回归: 后台增长漏斗与推荐评分页的列必须和后端真实返回对齐。
 *
 * 背景: 这两张表当前零写入(聚合任务没挂起来, 属「不修」范围), 但页面列名一旦和后端
 * resultMap 对不上, 等真有数据时列也永远读不出来, 运营会把「读不出」误当「没有」。
 * B2: 前端列 prop="eventName", 后端字段是 stepName ← step_name。
 * C:  前端渲染 title/status/publishStatus/delFlag, 后端 selectAdminScoreList 不返回这四列。
 */
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')
const repoRoot = path.resolve(uiRoot, '..')
const read = relativePath => fs.readFileSync(path.join(repoRoot, relativePath), 'utf8')

const analyticsSource = read('chengyinhub-ui/src/views/business/analytics/index.vue')
const funnelMapperSource = read('chengyinhub-system/src/main/resources/mapper/business/BizFunnelDailyMapper.xml')
const recommendationSource = read('chengyinhub-ui/src/views/business/recommendation/index.vue')
const recommendationMapperSource = read('chengyinhub-system/src/main/resources/mapper/business/RecommendationScoreDailyMapper.xml')

function mapperSelect(xml, id) {
  const at = xml.indexOf(`<select id="${id}"`)
  assert.ok(at >= 0, `mapper 缺 ${id}`)
  return xml.substring(at, xml.indexOf('</select>', at))
}

test('增长漏斗的事件列与后端 stepName 对齐，不再读不存在的 eventName', () => {
  assert.match(funnelMapperSource, /<result property="stepName"\s+column="step_name"/)
  assert.match(analyticsSource, /prop="stepName"/, '事件列必须绑后端返回的 stepName')
  assert.doesNotMatch(analyticsSource, /prop="eventName"/, 'eventName 后端不返回，点了永远是空白列')
})

test('推荐评分页只渲染后端 selectAdminScoreList 真实返回的列', () => {
  const sql = mapperSelect(recommendationMapperSource, 'selectAdminScoreList')
  for (const returned of ['stat_date', 'member_id', 'candidate_type', 'candidate_id', 'score', 'reason_codes', 'rank_position', 'create_time']) {
    assert.match(sql, new RegExp(returned), `selectAdminScoreList 必须返回 ${returned}`)
  }
  for (const ghost of ['title', 'status', 'publish_status', 'del_flag']) {
    assert.doesNotMatch(sql, new RegExp(`\\b${ghost}\\b`), `selectAdminScoreList 并不返回 ${ghost}`)
  }
  for (const ghostProp of ['title', 'status', 'publishStatus', 'delFlag']) {
    assert.doesNotMatch(recommendationSource, new RegExp(`prop="${ghostProp}"`), `页面不得再渲染后端不返回的 ${ghostProp}`)
  }
  for (const realProp of ['candidateType', 'candidateId', 'score', 'reasonCodes']) {
    assert.match(recommendationSource, new RegExp(`prop="${realProp}"`), `页面应保留后端真实返回的 ${realProp}`)
  }
})
