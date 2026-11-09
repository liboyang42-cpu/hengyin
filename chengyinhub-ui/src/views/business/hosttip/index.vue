<template>
  <div class="app-container">
    <el-form :inline="true" size="small" @submit.native.prevent>
      <el-form-item label="主持会员 ID">
        <el-input v-model="hostMemberId" clearable style="width: 180px" @keyup.enter.native="load" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" @click="load">查询</el-button>
      </el-form-item>
    </el-form>
    <p v-if="inputError" class="qa-error">{{ inputError }}</p>
    <p v-if="loadError" class="qa-error">{{ loadError }} <el-button type="text" @click="load">重试</el-button></p>
    <el-table v-else v-loading="loading" :data="rows" :empty-text="searched ? '该主持没有小费记录' : '输入主持会员 ID 查询'">
      <el-table-column label="意向" width="90">
        <template slot-scope="s">#{{ s.row.id }}</template>
      </el-table-column>
      <el-table-column label="顾客" width="110">
        <template slot-scope="s">#{{ s.row.payerMemberId }}</template>
      </el-table-column>
      <el-table-column label="俱乐部" width="100">
        <template slot-scope="s">{{ s.row.clubId ? '#' + s.row.clubId : '不限定' }}</template>
      </el-table-column>
      <el-table-column label="金额" width="120">
        <template slot-scope="s">{{ moneyText(s.row.amount, s.row.currency) }}</template>
      </el-table-column>
      <el-table-column label="归主持" width="90">
        <template slot-scope="s">{{ s.row.hostSharePercent === null || s.row.hostSharePercent === undefined ? '—' : s.row.hostSharePercent + '%' }}</template>
      </el-table-column>
      <el-table-column label="通道手续费" width="110">
        <template slot-scope="s">{{ feeBearerLabel(s.row.channelFeeBearer) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template slot-scope="s">{{ tipStatusLabel(s.row.status) }}</template>
      </el-table-column>
      <el-table-column label="通道单号" prop="externalTradeNo" min-width="160" />
      <el-table-column label="创建时间" width="160">
        <template slot-scope="s">{{ s.row.createTime ? parseTime(s.row.createTime, '{y}-{m}-{d} {h}:{i}') : '—' }}</template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script>
import { listHostTipIntents } from '@/api/business/hosttip'
import { businessError, moneyText, tipStatusLabel, feeBearerLabel } from '@/utils/questo-admin'

export default {
  name: 'BusinessHostTip',
  data() {
    return { hostMemberId: '', rows: [], loading: false, searched: false, inputError: '', loadError: '' }
  },
  created() {
    if (this.$route.query.hostMemberId) {
      this.hostMemberId = String(this.$route.query.hostMemberId)
      this.load()
    }
  },
  methods: {
    moneyText, tipStatusLabel, feeBearerLabel,
    load() {
      this.inputError = ''
      this.loadError = ''
      if (!/^\d+$/.test(String(this.hostMemberId).trim())) {
        this.inputError = '请输入数字会员 ID'
        return
      }
      this.loading = true
      listHostTipIntents(String(this.hostMemberId).trim()).then(res => {
        this.rows = res.data || []
        this.searched = true
      }).catch(err => {
        this.rows = []
        this.loadError = businessError(err).message
      }).finally(() => { this.loading = false })
    }
  }
}
</script>

<style scoped>
.qa-error { color: #F56C6C; font-size: 12px; margin: 4px 0 8px; }
</style>
