<template>
  <div class="app-container">
    <el-form ref="queryForm" :model="queryParams" size="small" :inline="true" label-width="80px">
      <el-form-item label="世界名称" prop="worldName">
        <el-input v-model="queryParams.worldName" placeholder="输入世界名称" clearable @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item label="城市编码" prop="cityCode">
        <el-input v-model="queryParams.cityCode" placeholder="输入城市编码" clearable @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button
          v-hasPermi="['business:world:edit']"
          type="primary"
          plain
          icon="el-icon-plus"
          size="mini"
          @click="openDialog"
        >新增世界</el-button>
      </el-col>
    </el-row>

    <el-table v-loading="loading" :data="worldList" empty-text="暂无IP世界">
      <el-table-column label="世界编码" align="center" prop="worldCode" min-width="140" />
      <el-table-column label="世界名称" align="center" prop="worldName" min-width="160" />
      <el-table-column label="副标题" align="left" prop="subtitle" min-width="180" show-overflow-tooltip />
      <el-table-column label="城市" align="center" prop="cityCode" width="120" />
      <el-table-column label="状态" align="center" prop="status" width="90" />
      <el-table-column label="排序" align="center" prop="sortNo" width="90" />
    </el-table>

    <el-dialog title="IP世界" :visible.sync="open" width="560px" append-to-body>
      <el-form :model="form" label-width="90px">
        <el-form-item label="世界编码">
          <el-input v-model="form.worldCode" />
        </el-form-item>
        <el-form-item label="世界名称">
          <el-input v-model="form.worldName" />
        </el-form-item>
        <el-form-item label="副标题">
          <el-input v-model="form.subtitle" />
        </el-form-item>
        <el-form-item label="城市编码">
          <el-input v-model="form.cityCode" />
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="form.intro" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确定</el-button>
        <el-button @click="open = false">取消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listWorlds, saveWorld } from '@/api/business/ipworld'

export default {
  name: 'BusinessWorld',
  data() {
    return {
      loading: false,
      open: false,
      worldList: [],
      queryParams: {
        worldName: null,
        cityCode: null
      },
      form: {
        worldCode: '',
        worldName: '',
        subtitle: '',
        cityCode: '',
        intro: '',
        status: '0',
        deleted: '0',
        sortNo: 0
      }
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      this.loading = true
      listWorlds(this.queryParams).then(response => {
        this.worldList = response.rows || []
      }).finally(() => {
        this.loading = false
      })
    },
    handleQuery() {
      this.getList()
    },
    resetQuery() {
      this.resetForm('queryForm')
      this.handleQuery()
    },
    openDialog() {
      this.open = true
      this.form = {
        worldCode: '',
        worldName: '',
        subtitle: '',
        cityCode: '',
        intro: '',
        status: '0',
        deleted: '0',
        sortNo: 0
      }
    },
    submitForm() {
      saveWorld(this.form).then(() => {
        this.$modal.msgSuccess('保存成功')
        this.open = false
        this.getList()
      })
    }
  }
}
</script>
