<template>
  <div class="app-container">
    <el-alert type="info" :closable="false" show-icon
      title="赞助任务只出现在玩家自行打开的「优惠」图层里,并始终标注「广告」。它不会改变迷雾地图、地点排序或搜索结果。" />
    <el-alert class="mt8" :type="chargingOpen ? 'warning' : 'info'" :closable="false" show-icon
      :title="chargingOpen ? '资金开关(商家资金)已打开:可以记录计费,计费只在预算内入账。' : '资金开关未打开:只展示,不计费。预算仅作计划,不产生任何扣费记录。'" />

    <el-form :inline="true" size="small" class="mt8">
      <el-form-item>
        <el-button v-hasPermi="['business:sponsoredQuest:edit']" type="primary" icon="el-icon-plus" @click="openCreate">新建赞助任务</el-button>
        <el-button icon="el-icon-refresh" @click="load">刷新</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows">
      <el-table-column label="编号" prop="id" width="70" />
      <el-table-column label="标签" width="70">
        <template slot-scope="s"><el-tag size="mini" type="warning">{{ s.row.label }}</el-tag></template>
      </el-table-column>
      <el-table-column label="任务" prop="title" />
      <el-table-column label="赞助方" prop="sponsorName" width="140" />
      <el-table-column label="地点数" prop="placeCount" width="70" />
      <el-table-column label="投放时间" width="200">
        <template slot-scope="s">{{ s.row.startTime }}<br>{{ s.row.endTime }}</template>
      </el-table-column>
      <el-table-column label="预算 / 已计费(元)" width="150">
        <template slot-scope="s">{{ yuan(s.row.budgetCent) }} / {{ yuan(s.row.spentCent) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template slot-scope="s">{{ statusText(s.row.status) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="240">
        <template slot-scope="s">
          <el-button v-if="s.row.status !== 3" v-hasPermi="['business:sponsoredQuest:edit']" type="text" @click="openEdit(s.row)">修改</el-button>
          <el-button v-if="s.row.status === 0 || s.row.status === 2" v-hasPermi="['business:sponsoredQuest:edit']" type="text" @click="setStatus(s.row, 1)">上线</el-button>
          <el-button v-if="s.row.status === 1" v-hasPermi="['business:sponsoredQuest:edit']" type="text" @click="setStatus(s.row, 2)">暂停</el-button>
          <el-button v-if="s.row.status !== 3" v-hasPermi="['business:sponsoredQuest:edit']" type="text" @click="setStatus(s.row, 3)">结束</el-button>
          <el-button type="text" @click="openCharges(s.row)">计费记录</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog :title="form.id ? '修改赞助任务' : '新建赞助任务'" :visible.sync="formOpen" width="640px" append-to-body>
      <el-alert type="info" :closable="false" title="玩家看到的任务固定标注「广告」,该标签不能修改或去掉。" />
      <el-form ref="form" :model="form" label-width="110px" size="small" class="mt8">
        <el-form-item label="赞助方" required>
          <el-input v-model="form.sponsorName" maxlength="64" show-word-limit />
        </el-form-item>
        <el-form-item label="赞助商家编号">
          <el-input v-model="form.sponsorMerchantId" placeholder="可选,商家 ID" style="width: 200px" />
        </el-form-item>
        <el-form-item label="任务标题" required>
          <el-input v-model="form.title" maxlength="64" show-word-limit />
        </el-form-item>
        <el-form-item label="任务内容" required>
          <el-input v-model="form.content" type="textarea" :rows="4" maxlength="1000" show-word-limit />
        </el-form-item>
        <el-form-item label="封面图">
          <el-input v-model="form.coverImg" placeholder="可选,https 地址" />
        </el-form-item>
        <el-form-item label="地点编号" required>
          <el-input v-model="form.placeText" placeholder="已上架地点的 ID,用逗号分隔,最多 20 个" />
        </el-form-item>
        <el-form-item label="投放时间" required>
          <el-date-picker v-model="form.window" type="datetimerange" value-format="yyyy-MM-dd HH:mm:ss"
            range-separator="至" start-placeholder="开始" end-placeholder="结束" />
        </el-form-item>
        <el-form-item label="预算(元)">
          <el-input-number v-model="form.budgetYuan" :min="0" :precision="2" :step="100" />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="formOpen = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </div>
    </el-dialog>

    <el-dialog title="计费记录" :visible.sync="chargesOpen" width="640px" append-to-body>
      <el-alert v-if="!chargingOpen" type="info" :closable="false" show-icon title="资金开关未打开:不能记录计费。" />
      <el-form v-else :inline="true" size="small">
        <el-form-item label="计费单号">
          <el-input v-model="charge.refKey" placeholder="如 2026-11-w3" style="width: 140px" />
        </el-form-item>
        <el-form-item label="金额(元)">
          <el-input-number v-model="charge.amountYuan" :min="0.01" :precision="2" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="charge.reason" style="width: 160px" />
        </el-form-item>
        <el-form-item>
          <el-button v-hasPermi="['business:sponsoredQuest:charge']" type="primary" @click="submitCharge">记录</el-button>
        </el-form-item>
      </el-form>
      <el-table :data="charges" class="mt8">
        <el-table-column label="计费单号" prop="refKey" />
        <el-table-column label="金额(元)" width="100">
          <template slot-scope="s">{{ yuan(s.row.amountCent) }}</template>
        </el-table-column>
        <el-table-column label="说明" prop="reason" />
        <el-table-column label="操作人" prop="createBy" width="90" />
        <el-table-column label="时间" prop="createTime" width="160" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script>
import {
  listSponsoredQuests, getSponsoredQuestState, getSponsoredQuest, addSponsoredQuest, updateSponsoredQuest,
  setSponsoredQuestStatus, listSponsoredQuestCharges, chargeSponsoredQuest
} from '@/api/business/sponsoredQuest'

function emptyForm() {
  return { id: null, sponsorName: '', sponsorMerchantId: '', title: '', content: '', coverImg: '', placeText: '', window: [], budgetYuan: 0 }
}

export default {
  name: 'SponsoredQuest',
  data() {
    return {
      loading: false,
      rows: [],
      chargingOpen: false,
      formOpen: false,
      form: emptyForm(),
      chargesOpen: false,
      chargeTarget: null,
      charges: [],
      charge: { refKey: '', amountYuan: 0.01, reason: '' }
    }
  },
  created() {
    this.load()
  },
  methods: {
    load() {
      this.loading = true
      getSponsoredQuestState().then(res => { this.chargingOpen = !!(res.data && res.data.chargingOpen) })
      listSponsoredQuests().then(res => { this.rows = res.data || [] }).finally(() => { this.loading = false })
    },
    yuan(cent) {
      return ((Number(cent) || 0) / 100).toFixed(2)
    },
    statusText(status) {
      return { 0: '草稿', 1: '投放中', 2: '已暂停', 3: '已结束' }[status] || '未知'
    },
    openCreate() {
      this.form = emptyForm()
      this.formOpen = true
    },
    openEdit(row) {
      getSponsoredQuest(row.id).then(res => {
        const d = res.data || {}
        this.form = {
          id: d.id,
          sponsorName: d.sponsorName || '',
          sponsorMerchantId: d.sponsorMerchantId || '',
          title: d.title || '',
          content: d.content || '',
          coverImg: d.coverImg || '',
          placeText: (d.placeIds || []).join(','),
          window: [d.startTime, d.endTime],
          budgetYuan: (Number(d.budgetCent) || 0) / 100
        }
        this.formOpen = true
      })
    },
    save() {
      const placeIds = String(this.form.placeText).split(/[,，\s]+/).filter(Boolean).map(Number)
      if (placeIds.some(id => !Number.isInteger(id) || id <= 0)) { this.$modal.msgError('地点编号无效'); return }
      if (!this.form.window || this.form.window.length !== 2) { this.$modal.msgError('请填写投放开始和结束时间'); return }
      const merchant = Number(String(this.form.sponsorMerchantId).trim())
      const body = {
        id: this.form.id,
        sponsorName: this.form.sponsorName,
        sponsorMerchantId: Number.isInteger(merchant) && merchant > 0 ? merchant : null,
        title: this.form.title,
        content: this.form.content,
        coverImg: this.form.coverImg,
        placeIds,
        startTime: this.form.window[0],
        endTime: this.form.window[1],
        budgetCent: Math.round((Number(this.form.budgetYuan) || 0) * 100)
      }
      const call = body.id ? updateSponsoredQuest(body) : addSponsoredQuest(body)
      call.then(() => {
        this.$modal.msgSuccess('已保存')
        this.formOpen = false
        this.load()
      })
    },
    setStatus(row, status) {
      const words = { 1: '上线后玩家会在「优惠」图层看到这个任务(标注「广告」)。', 2: '暂停后玩家不再看到这个任务。', 3: '结束后不能再恢复。' }
      this.$modal.confirm(words[status]).then(() => setSponsoredQuestStatus(row.id, status)).then(() => this.load()).catch(() => {})
    },
    openCharges(row) {
      this.chargeTarget = row
      this.charge = { refKey: '', amountYuan: 0.01, reason: '' }
      this.chargesOpen = true
      this.loadCharges()
    },
    loadCharges() {
      listSponsoredQuestCharges(this.chargeTarget.id).then(res => { this.charges = res.data || [] })
    },
    submitCharge() {
      const cent = Math.round((Number(this.charge.amountYuan) || 0) * 100)
      chargeSponsoredQuest(this.chargeTarget.id, this.charge.refKey, cent, this.charge.reason).then(() => {
        this.$modal.msgSuccess('已记录')
        this.loadCharges()
        this.load()
      })
    }
  }
}
</script>
