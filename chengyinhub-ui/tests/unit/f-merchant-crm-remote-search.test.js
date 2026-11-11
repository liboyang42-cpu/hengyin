'use strict'
// f-merchant(P1-016 / CRM,2026-11-11):后台客户 CRM 的商家条件改为按店名远程搜索,不再手填 mms_merchant.member_id。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const SFC = fs.readFileSync(path.join(__dirname, '../../src/views/business/crm/index.vue'), 'utf8')

test('商家条件是远程搜索下拉,值取商家主体用户 memberId(CRM 的 merchantId 口径)', () => {
  assert.match(SFC, /<el-select\s+v-model="queryParams\.merchantId"[\s\S]*?filterable[\s\S]*?remote[\s\S]*?:remote-method="searchMerchants"/)
  assert.match(SFC, /:value="m\.memberId"/)
  assert.doesNotMatch(SFC, /placeholder="mms_merchant\.member_id"/, '不再让运营手填库字段')
  assert.doesNotMatch(SFC, /请先输入 mms_merchant/, '提示不再出现库字段名')
})

test('搜索走现有商家列表接口按店名查,失败不当成没有商家,迟到结果不覆盖', () => {
  assert.match(SFC, /import \{ listMerchant \} from '@\/api\/business\/merchant'/)
  assert.match(SFC, /listMerchant\(\{ name: keyword, pageNum: 1, pageSize: 20 \}\)/)
  assert.match(SFC, /seq === this\.merchantSearchSeq/)
  assert.match(SFC, /商家搜索失败，请重试/)
})
