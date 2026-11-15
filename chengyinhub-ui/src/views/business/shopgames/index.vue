<template>
  <div class="app-container">
    <el-alert type="info" :closable="false" show-icon
      title="每家店按店铺类型配一个小游戏池,商家在池里挑选;免费版一家店一个小游戏,套餐只增加可选数量。所有小游戏都按同一标准进行:三轮,赢两轮算过关,奖励相同。赞助和推广不会改变玩哪个游戏或难度。" />
    <el-tabs v-model="tab" class="mt8" @tab-click="load">
      <el-tab-pane label="小游戏池" name="pools">
        <el-alert v-if="fairnessProblems.length" type="warning" :closable="false" show-icon class="mt8"
          :title="'游戏池不再均衡:' + fairnessProblems.join('、')" />
        <el-table v-loading="loading" :data="kinds" class="mt8">
          <el-table-column label="店铺类型" min-width="120">
            <template slot-scope="s">{{ s.row.nameZh }}</template>
          </el-table-column>
          <el-table-column label="可选小游戏(第一个为默认)" min-width="320">
            <template slot-scope="s">{{ s.row.pool.map(g => g.nameZh + ' · 约' + g.seconds + '秒').join(' / ') }}</template>
          </el-table-column>
        </el-table>
        <el-table v-loading="loading" :data="games" class="mt8">
          <el-table-column label="小游戏" min-width="140">
            <template slot-scope="s">{{ s.row.nameZh }}</template>
          </el-table-column>
          <el-table-column label="时长" width="90">
            <template slot-scope="s">约 {{ s.row.seconds }} 秒</template>
          </el-table-column>
          <el-table-column label="需要" min-width="120">
            <template slot-scope="s">{{ needsText(s.row) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template slot-scope="s">{{ s.row.enabled ? '上架' : '已下架' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="110">
            <template slot-scope="s">
              <el-button v-hasPermi="['business:shopgames:edit']" type="text" @click="toggleGame(s.row)">
                {{ s.row.enabled ? '下架' : '重新上架' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="单店设置" name="shops">
        <el-form :inline="true" class="mt8" @submit.native.prevent>
          <el-form-item label="商家编号">
            <el-input v-model="query.merchantId" placeholder="商家编号" clearable style="width: 160px" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="el-icon-search" @click="loadShops">查询</el-button>
          </el-form-item>
        </el-form>
        <el-table v-loading="loading" :data="shops">
          <el-table-column label="商家" min-width="140">
            <template slot-scope="s">{{ s.row.merchantName || '商家' }}(编号 {{ s.row.merchantId }})</template>
          </el-table-column>
          <el-table-column label="店铺类型" width="120">
            <template slot-scope="s">{{ s.row.kind.nameZh }}{{ s.row.kindOverride ? '(平台指定)' : '' }}</template>
          </el-table-column>
          <el-table-column label="当前小游戏" min-width="200">
            <template slot-scope="s">{{ gamesText(s.row.games) }}</template>
          </el-table-column>
          <el-table-column label="来源" width="100">
            <template slot-scope="s">{{ sourceText(s.row.source) }}</template>
          </el-table-column>
          <el-table-column label="可选数量" width="90">
            <template slot-scope="s">{{ s.row.allowance }}</template>
          </el-table-column>
          <el-table-column label="锁定" width="70">
            <template slot-scope="s">{{ s.row.locked ? '是' : '否' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template slot-scope="s">
              <el-button v-hasPermi="['business:shopgames:edit']" type="text" @click="openShop(s.row)">设置</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog title="单店小游戏设置" :visible.sync="shopOpen" width="560px" append-to-body>
      <el-form :model="shopForm" label-width="110px">
        <el-form-item label="店铺类型">
          <el-select v-model="shopForm.kindOverride" clearable placeholder="按商家行业自动判断">
            <el-option v-for="k in kinds" :key="k.code" :label="k.nameZh" :value="k.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="平台指定小游戏">
          <el-select v-model="shopForm.games" multiple :multiple-limit="3" clearable placeholder="不指定则用商家自己的选择">
            <el-option v-for="g in enabledGames" :key="g.key" :label="g.nameZh" :value="g.key" />
          </el-select>
        </el-form-item>
        <el-form-item label="锁定">
          <el-switch v-model="shopForm.locked" />
          <span class="ml8">锁定后商家不能自行修改</span>
        </el-form-item>
        <el-form-item label="修改原因"><el-input v-model="shopForm.reason" maxlength="255" /></el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="shopOpen = false">取消</el-button>
        <el-button type="primary" :disabled="!shopForm.reason || !shopForm.reason.trim()" @click="saveShop">保存</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { getShopGameCatalog, listShopGameProfiles, updateShopGameProfile, setShopGameEnabled } from '@/api/business/shopGames'

export default {
  name: 'ShopGames',
  data() {
    return {
      tab: 'pools',
      loading: false,
      games: [],
      kinds: [],
      fairnessProblems: [],
      shops: [],
      query: { merchantId: '' },
      shopOpen: false,
      shopForm: { merchantId: null, kindOverride: '', games: [], locked: false, reason: '' }
    }
  },
  computed: {
    enabledGames() {
      return this.games.filter(g => g.enabled)
    }
  },
  created() {
    this.loadCatalog()
  },
  methods: {
    load() {
      return this.tab === 'shops' ? this.loadShops() : this.loadCatalog()
    },
    loadCatalog() {
      this.loading = true
      return getShopGameCatalog().then(res => {
        const d = res.data || {}
        this.games = d.games || []
        this.kinds = d.kinds || []
        this.fairnessProblems = d.fairnessProblems || []
      }).finally(() => { this.loading = false })
    },
    loadShops() {
      this.loading = true
      const id = String(this.query.merchantId || '').trim()
      return Promise.all([this.games.length ? null : this.loadCatalog(),
        listShopGameProfiles(id ? { merchantId: id } : {})]).then(([, res]) => {
        this.shops = res.data || []
      }).finally(() => { this.loading = false })
    },
    needsText(g) {
      const parts = []
      if (g.needsMotion) parts.push('走动或手机感应')
      if (g.needsCamera) parts.push('拍照')
      return parts.length ? parts.join('、') : '只需作答'
    },
    gamesText(games) {
      if (!games || !games.length) return '暂无可用小游戏'
      return games.map(g => g.nameZh + (g.ready ? '' : '(商家还没配好内容)')).join(' / ')
    },
    sourceText(source) {
      return { OPERATOR: '平台指定', MERCHANT: '商家选择', DEFAULT: '默认' }[source] || '默认'
    },
    askReason(title) {
      return this.$prompt('原因(会记入操作记录)', title, {
        inputValidator: v => (v && v.trim().length > 0) || '请填写原因',
        confirmButtonText: '确定',
        cancelButtonText: '取消'
      }).then(({ value }) => value.trim())
    },
    done(res, reload) {
      if (res && res.code === 200) this.$modal.msgSuccess('已完成')
      else if (res && res.msg) this.$modal.msgError(res.msg)
      return reload()
    },
    toggleGame(row) {
      this.askReason((row.enabled ? '下架 ' : '重新上架 ') + row.nameZh)
        .then(reason => setShopGameEnabled(row.key, { enabled: !row.enabled, reason }))
        .then(res => this.done(res, this.loadCatalog)).catch(() => {})
    },
    openShop(row) {
      this.shopForm = {
        merchantId: row.merchantId,
        kindOverride: row.kindOverride || '',
        games: (row.operatorGames || []).map(g => g.key),
        locked: !!row.locked,
        reason: ''
      }
      this.shopOpen = true
    },
    saveShop() {
      const f = this.shopForm
      updateShopGameProfile(f.merchantId, {
        kindOverride: f.kindOverride || '',
        games: f.games.map(key => ({ key })),
        locked: f.locked,
        reason: f.reason.trim()
      }).then(res => {
        this.shopOpen = false
        return this.done(res, this.loadShops)
      }).catch(() => {})
    }
  }
}
</script>
