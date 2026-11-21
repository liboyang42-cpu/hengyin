'use strict'
// 2026-11-21 业主(新玩家走《这条街没有丢过猫》):详情页「活动内容」只讲故事引子;
// 安全 / 营业时间 / 路线这类实用话,和价格、能否暂停续玩、几人同票、通关得什么,收成一张短短的「出发前」清单。
// 只搬已有的字和字段,不编事实:拿不到的那一条就不出。

// 作者在正文里写的小标题(【注意事项】、温馨提示：…):从它起到这一段结束都算实用话
const HEADING = /^[【\[〔(（]?\s*(出发前|注意事项|温馨提示|安全提示|安全须知|游玩须知|参与须知|活动须知|须知|小贴士|贴士|提示|注意|tips|路线|路线安排|营业时间|开放时间|交通|集合)\s*[】\]〕)）]?\s*[:：]?/i
// 单句判据:说的是安全、营业 / 开放时间、交通、携带、路线顺序 —— 不是故事
const PRACTICAL = /停稳|看手机|斑马线|过马路|红绿灯|注意安全|安全第一|营业时间|营业|闭店|打烊|休息日|店休|开放时间|请勿|切勿|禁止|不要在|雨天|穿舒适|舒适的鞋|携带|带好|充电宝|电量|地铁|公交|停车|卫生间|洗手间|厕所|全程约|步行约|路线[:：]|→|➜|⇒/
const LIST_MARK = /^\s*(?:[-*•·●▪]|\d{1,2}\s*[.、)）]|[①-⑳]|[（(]\d{1,2}[)）])\s*/

function clean(s) {
  return String(s || '').replace(LIST_MARK, '').replace(/^[\s:：,，、]+/, '').replace(/[\s。；;，,、]+$/, '').trim()
}

function sentences(line) {
  return String(line).match(/[^。！？!?；;]+[。！？!?；;]*/g) || []
}

/**
 * 正文拆成「故事」和「实用话」。按段(空行)、行、句三级:
 *   · 段首是作者小标题(注意事项 / 温馨提示 / 路线…)→ 整段是实用话;
 *   · 以列表记号(1. / · / -)开头的行 → 实用话(路线清单、规则条目);
 *   · 其余逐句看:命中实用判据的句子拿出来,剩下的留在故事里。
 * @returns {{ story: string, notes: string[] }}
 */
function splitDescription(text) {
  const raw = String(text == null ? '' : text).replace(/\r\n?/g, '\n').trim()
  if (!raw || raw === 'null') return { story: '', notes: [] }
  const notes = []
  const storyParas = []
  raw.split(/\n\s*\n/).forEach((para) => {
    const lines = para.split('\n').map((l) => l.trim()).filter(Boolean)
    let practical = false
    const keep = []
    lines.forEach((line) => {
      const head = line.match(HEADING)
      if (head) {
        practical = true
        const rest = line.slice(head[0].length)
        sentences(rest).forEach((s) => { const c = clean(s); if (c) notes.push(c) })
        return
      }
      if (practical || LIST_MARK.test(line)) {
        sentences(line).forEach((s) => { const c = clean(s); if (c) notes.push(c) })
        return
      }
      let story = ''
      sentences(line).forEach((s) => {
        if (PRACTICAL.test(s)) { const c = clean(s); if (c) notes.push(c) } else story += s
      })
      if (story.trim()) keep.push(story.trim())
    })
    if (keep.length) storyParas.push(keep.join('\n'))
  })
  const seen = {}
  return { story: storyParas.join('\n\n'), notes: notes.filter((n) => (seen[n] ? false : (seen[n] = true))) }
}

/**
 * 「出发前」清单:价格与包含 / 几人同票 / 暂停续玩 / 通关得什么 / 正文里拆出来的实用话。每条一行。
 * @param {object} info 主题详情(TopicInfoVO,已解码)
 * @param {{ buyOpts?: Array, includes?: string[], notes?: string[] }} extra
 */
function buildDepartNotes(info, extra) {
  const d = info || {}
  const x = extra || {}
  const out = []
  ;(x.buyOpts || []).forEach((o) => {
    if (!o || !o.name || !o.priceText) return
    out.push(o.name + ' ' + o.priceText + (o.people >= 2 ? ' · ' + o.people + ' 人同玩一张票' : ''))
  })
  const includes = (x.includes || []).filter((t) => /个章节$|个节点$|个玩法$|^语音导览/.test(t))
  if (includes.length) out.push('含 ' + includes.join('、'))
  // 城市定向可暂停,下次从暂停处接着玩(play 页 _restoreRunSession);自由探索没有一局的概念
  if (Number(d.productType) !== 2) out.push('中途可暂停，下次接着玩')
  if (d.finishMedalName || d.finishMedalImg) out.push('通关得勋章「' + (d.finishMedalName || (d.name ? d.name + ' · 通关' : '通关纪念')) + '」')
  if (d.completeRewardCouponId) out.push('通关后可领一张优惠券')
  return out.concat(x.notes || [])
}

module.exports = { splitDescription, buildDepartNotes }
