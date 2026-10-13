import request from '@/utils/request'

// 探店日现场七段漏斗 + G2 四项（只读）。
//
// 为什么必须有后台读面：G2 是**硬门**（覆盖率 <95% 或单店等待 P90 >20 分钟 ⇒ 判门失败），
// 而在此之前它算得出、没有任何人能在控制台看到 verdict —— 验收只能 curl。
// 权限复用 business:topic:query，不新开权限点、不需要菜单迁移。
export function getTopicFunnel(topicId) {
  return request({
    url: '/business/topicFunnel/' + topicId,
    method: 'get'
  })
}
