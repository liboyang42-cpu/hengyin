<template>
  <div class="app-container cy-domain-page">
    <cy-page-header
      eyebrow="用户与客服"
      title="用户调证台"
      description="默认脱敏查看用户身份、状态、成长与资金入口。客服从同一用户对象下钻积分和余额明细，不再复制 ID 跨菜单。"
    >
      <el-button icon="el-icon-download" @click="handleExport" v-hasPermi="['business:member:export']">导出当前筛选</el-button>
    </cy-page-header>

    <section class="member-overview" aria-label="本页用户概览">
      <div v-for="item in memberOverview" :key="item.label" class="member-overview__item">
        <span>{{ item.label }}</span><strong>{{ item.value }}</strong><small>{{ item.note }}</small>
      </div>
    </section>

    <section class="member-filter">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px">
      <el-form-item label="用户类型" prop="userType">
          <DictRadio v-model="queryParams.userType" @change="handleQuery" size="small"
                     :radioData="dict.type.sys_user_type" :showAll="'all'"/>
        </el-form-item>
      <el-form-item label="手机号码" prop="phone">
        <el-input
          v-model="queryParams.phone"
          placeholder="请输入手机号码"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="真实姓名" prop="name">
        <el-input
          v-model="queryParams.name"
          placeholder="请输入真实姓名"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>
    </section>

    <el-table v-loading="loading" :data="memberList">
      <el-table-column label="用户" prop="nickname" min-width="170">
          <template slot-scope="scope">
            <button type="button" class="member-entity" @click="openMemberDetail(scope.row)">
              <el-avatar :size="36" :src="scope.row.avatar" />
              <span><strong>{{ scope.row.nickname || '未设置昵称' }}</strong><small>#{{ scope.row.id }}</small></span>
            </button>
          </template>
        </el-table-column>
      <el-table-column label="ID" align="center" prop="id" />
      <el-table-column label="用户类型" align="center" prop="userType" width="110">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.sys_user_type" :value="scope.row.userType" />
        </template> 
      </el-table-column>

      <el-table-column label="手机号码" align="center" prop="phone" width="120" />
      <!-- 修改会员等级列，添加编辑功能 -->
      <el-table-column label="会员等级" align="center" prop="levelId" width="120">
        <template slot-scope="scope">
          <el-link icon="el-icon-edit" @click="handleLevel(scope.row)">
            <dict-tag :options="dict.type.sys_user_level" :value="scope.row.levelId" />
          </el-link>
        </template>
      </el-table-column>
      <el-table-column label="真实姓名" align="center" prop="name" />
      <el-table-column label="性别" align="center" prop="sex">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.sys_user_sex" :value="scope.row.sex" />
        </template>
      </el-table-column>
      <el-table-column label="积分" align="center" prop="point" />
      <el-table-column label="余额" align="center" prop="balance" />
      <el-table-column label="状态" align="center" prop="status">
        <template slot-scope="scope">
          <cy-status-tag :label="memberStatusLabel(scope.row.status)" :tone="memberStatusTone(scope.row.status)" />
        </template>
      </el-table-column>
      <el-table-column label="注册时间" align="center" prop="createTime" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.createTime, '{y}-{m}-{d}') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" fixed="right" width="180" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button size="mini" icon="el-icon-view" type="text" @click="openMemberDetail(scope.row)">用户档案</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-edit"
            @click="handleStatus(scope.row)"
            v-hasPermi="['business:member:edit']"
          >修改状态</el-button>
        </template>
      </el-table-column>
    </el-table>
    
    <pagination
      v-show="total>0"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      @pagination="getList"
    />

    <el-drawer title="用户 360 调证" :visible.sync="detailOpen" size="560px" append-to-body>
      <div v-if="detailMember" class="member-detail">
        <div class="member-detail__hero">
          <el-avatar :size="58" :src="detailMember.avatar" />
          <div><h2>{{ detailMember.nickname || '未设置昵称' }}</h2><p>用户 #{{ detailMember.id }} · {{ detailMember.phone || '手机号已脱敏/未绑定' }}</p></div>
          <cy-status-tag :label="memberStatusLabel(detailMember.status)" :tone="memberStatusTone(detailMember.status)" />
        </div>
        <dl class="member-detail__facts">
          <div><dt>真实姓名</dt><dd>{{ detailMember.name || '未提供/已脱敏' }}</dd></div>
          <div><dt>用户类型</dt><dd>{{ detailMember.userType || '—' }}</dd></div>
          <div><dt>会员等级</dt><dd>{{ detailMember.levelId || '—' }}</dd></div>
          <div><dt>积分</dt><dd>{{ detailMember.point || 0 }}</dd></div>
          <div><dt>余额</dt><dd>¥{{ Number(detailMember.balance || 0).toFixed(2) }}</dd></div>
          <div><dt>注册时间</dt><dd>{{ parseTime(detailMember.createTime, '{y}-{m}-{d}') || '—' }}</dd></div>
        </dl>
        <section class="member-detail__actions">
          <h3>关联调证</h3>
          <p>下钻读取真实明细；状态、积分与余额事实不在本抽屉直接修改。</p>
          <el-button icon="el-icon-tickets" @click="viewPointsDetail(detailMember.id)">积分明细</el-button>
          <el-button icon="el-icon-wallet" @click="viewBalanceDetail(detailMember.id)">余额明细</el-button>
          <el-button icon="el-icon-data-analysis" v-hasPermi="['business:member:query']" @click="viewGrowthDetail(detailMember.id)">成长档案</el-button>
        </section>
      </div>
    </el-drawer>

    <!--积分明细-->
    <points-detail v-if="pointsDetailVisible" ref="pointsDetail" @refreshDataList="getList"></points-detail>

    <!--余额明显-->
    <balance-detail v-if="balanceDetailVisible" ref="balanceDetail" @refreshDataList="getList"></balance-detail>

    <!-- 修改状态对话框 -->
    <el-dialog title="修改状态" :visible.sync="dialogStatus" width="600px" append-to-body :close-on-click-modal="false">
      <el-form ref="form" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="状态" prop="status">
          <DictRadio v-model="form.status" :radioData="dict.type.sys_user_status" size="small" :show-all="'false'"
            :filter="['1,2']">
          </DictRadio>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>

    <!-- 修改会员等级对话框 -->
    <el-dialog title="修改会员等级" :visible.sync="dialogLevel" width="500px" append-to-body :close-on-click-modal="false">
      <el-form ref="levelForm" :model="levelForm" :rules="levelRules" label-width="100px">
        <el-form-item label="当前用户" prop="nickname">
          <el-input v-model="levelForm.nickname" :disabled="true" />
        </el-form-item>
        <el-form-item label="用户类型" prop="userType">
          <dict-tag :options="dict.type.sys_user_type" :value="levelForm.userType" />
        </el-form-item>
        <el-form-item label="会员等级" prop="levelId">
          <el-radio-group v-model="levelForm.levelId">
            <el-radio 
              v-for="dict in filteredLevelOptions" 
              :key="dict.value" 
              :label="dict.value"
            >{{ dict.label }}</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitLevelForm">确 定</el-button>
        <el-button @click="cancelLevel">取 消</el-button>
      </div>
    </el-dialog>

  </div>
</template>

<script>
import { listMember, getMember, updateMember } from "@/api/business/member"
import PointsDetail from './points-detail.vue'
import BalanceDetail from './balance-detail.vue'
import CyPageHeader from '@/components/CyPageHeader'
import CyStatusTag from '@/components/CyStatusTag'

export default {
  components: { PointsDetail, BalanceDetail, CyPageHeader, CyStatusTag },
  name: "Member",
  dicts: ['sys_user_status','sys_user_type','sys_user_level','sys_user_sex'],
  data() {
    return {
      // 遮罩层
      loading: true,
      // 显示搜索条件
      showSearch: true,
      // 总条数
      total: 0,
      // 用户表格数据
      memberList: [],
      dialogStatus: false,
      dialogLevel: false, // 会员等级修改对话框
      pointsDetailVisible: false,
      balanceDetailVisible: false,
      detailOpen: false,
      detailMember: null,
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        userType: null,
        phone: null,
        name: null,
        status: null
      },
      // 表单参数
      form: {},
      // 会员等级表单参数
      levelForm: {
        id: null,
        nickname: null,
        userType: null,
        levelId: null
      },
      // 表单校验
      rules: {
      },
      // 会员等级表单校验
      levelRules: {
        levelId: [
          { required: true, message: "会员等级不能为空", trigger: "blur" }
        ]
      }
    }
  },
  computed: {
    memberOverview() {
      const active = this.memberList.filter(item => Number(item.status) === 0).length
      const restricted = this.memberList.filter(item => Number(item.status) !== 0).length
      const balance = this.memberList.reduce((sum, item) => sum + Number(item.balance || 0), 0)
      return [
        { label: '当前页用户', value: this.memberList.length, note: `筛选共 ${this.total} 人` },
        { label: '正常状态', value: active, note: '当前页可用账号' },
        { label: '受限 / 注销', value: restricted, note: '当前页需关注' },
        { label: '用户余额合计', value: `¥${balance.toFixed(2)}`, note: '仅当前页只读汇总' }
      ]
    },
    // 根据用户类型过滤会员等级选项
    filteredLevelOptions() {
      console.log('当前用户类型:', this.levelForm.userType);
      console.log('所有等级选项:', this.dict.type.sys_user_level);
      
      if (!this.dict || !this.dict.type || !this.dict.type.sys_user_level) {
        console.log('字典数据未加载');
        return []
      }
      
      const allLevels = this.dict.type.sys_user_level
      const userType = this.levelForm.userType
      
      // 确保userType是字符串类型进行比较
      const userTypeStr = String(userType)
      
      let filteredLevels = []
      
      if (userTypeStr === '1') {
        // 用户类型为1时显示 0,1,2,5
        filteredLevels = allLevels.filter(level => 
          ['0', '1', '2', '5'].includes(String(level.value))
        )
        console.log('用户类型1过滤后的等级:', filteredLevels);
      } else if (userTypeStr === '2') {
        // 用户类型为2时显示 3,4
        filteredLevels = allLevels.filter(level => 
          ['3', '4'].includes(String(level.value))
        )
        console.log('用户类型2过滤后的等级:', filteredLevels);
      } else {
        // 如果没有匹配的用户类型，显示所有等级
        filteredLevels = allLevels
        console.log('未匹配用户类型，显示所有等级:', filteredLevels);
      }
      
      return filteredLevels
    }
  },
  created() {
    this.ensureCanonicalRoute()
  },
  watch: {
    '$route'() {
      this.ensureCanonicalRoute()
    }
  },
  methods: {
    ensureCanonicalRoute() {
      if (String(this.$route.path || '').replace(/\/+$/, '') !== '/customer/member') {
        this.$router.replace({ name: 'customerMember', query: Object.assign({}, this.$route.query) })
        return
      }
      this.getList()
    },
    memberStatusLabel(status) {
      return { 0: '正常', 1: '停用', 2: '注销' }[Number(status)] || '未知状态'
    },
    memberStatusTone(status) {
      return { 0: 'success', 1: 'warning', 2: 'danger' }[Number(status)] || 'neutral'
    },
    openMemberDetail(row) {
      this.detailOpen = true
      this.detailMember = row
      getMember(row.id).then(response => { this.detailMember = response.data || row })
    },
    /** 修改会员等级 */
    handleLevel(row) {
      this.resetLevelForm()
      getMember(row.id).then(response => {
        console.log('获取到的用户数据:', response.data);
        this.levelForm = {
          id: response.data.id,
          nickname: response.data.nickname,
          userType: response.data.userType,
          levelId: response.data.levelId
        }
        this.dialogLevel = true
        
        // 确保对话框显示后再计算过滤选项
        this.$nextTick(() => {
          console.log('过滤后的等级选项:', this.filteredLevelOptions);
        })
      })
    },
    
    /** 提交会员等级修改 */
    submitLevelForm() {
      this.$refs["levelForm"].validate(valid => {
        if (valid) {
          // 构建更新数据
          const updateData = {
            id: this.levelForm.id,
            levelId: this.levelForm.levelId
          }
          
          updateMember(updateData).then(response => {
            this.$modal.msgSuccess("修改成功")
            this.dialogLevel = false
            this.getList()
          }).catch(() => {})
        }
      })
    },
    
    /** 取消会员等级修改 */
    cancelLevel() {
      this.dialogLevel = false
      this.resetLevelForm()
    },
    
    /** 重置会员等级表单 */
    resetLevelForm() {
      this.levelForm = {
        id: null,
        nickname: null,
        userType: null,
        levelId: null
      }
      this.resetForm("levelForm")
    },

    viewPointsDetail(member_id) {
      this.pointsDetailVisible = true
      this.$nextTick(() => {
        if (this.$refs.pointsDetail) {
          this.$refs.pointsDetail.init(member_id)
        }
      })
    },
    
    viewBalanceDetail(member_id) {
      this.balanceDetailVisible = true
      this.$nextTick(() => {
        if (this.$refs.balanceDetail) {
          this.$refs.balanceDetail.init(member_id)
        }
      })
    },

    viewGrowthDetail(memberId) {
      this.$router.push({ name: 'memberGrowthDetail', params: { memberId: String(memberId) } })
    },
    
    /** 修改用户状态 */
    handleStatus(row) {
      this.form = { id: row.id, status: row.status }
      this.dialogStatus = true
    },

    /** 查询用户列表 */
    getList() {
      this.loading = true
      listMember(this.queryParams).then(response => {
        this.memberList = response.rows
        this.total = response.total
        this.loading = false
      })
    },
    
    // 取消按钮
    cancel() {
      this.dialogStatus = false
      this.reset()
    },
    
    // 表单重置
    reset() {
      this.form = {
        id: null,
        status: null
      }
      this.resetForm("form")
    },
    
    /** 搜索按钮操作 */
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    
    /** 重置按钮操作 */
    resetQuery() {
      this.resetForm("queryForm")
      this.handleQuery()
    },
    
    /** 提交按钮 */
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          updateMember({ id: this.form.id, status: this.form.status }).then(() => {
            this.$modal.msgSuccess("修改成功")
            this.dialogStatus = false
            this.getList()
          })
        }
      })
    },
    
    /** 导出按钮操作 */
    handleExport() {
      this.download('business/member/export', {
        ...this.queryParams
      }, `member_${new Date().getTime()}.xlsx`)
    }
  }
}
</script>

<style lang="scss" scoped>
.cy-domain-page { color: var(--cy-color-text-primary); }
.member-overview { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--cy-space-2-5); margin-bottom: 20px; }
.member-overview__item { padding: var(--cy-space-3-5); border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); background: var(--cy-color-bg-surface); box-shadow: var(--cy-shadow-card); }
.member-overview span, .member-overview strong, .member-overview small { display: block; }
.member-overview span { color: var(--cy-color-text-secondary); font-size: 12px; }
.member-overview strong { margin: 8px 0 var(--cy-legacy-3); font-size: 24px; }
.member-overview small { color: var(--cy-color-text-tertiary); }
.member-filter { margin-bottom: var(--cy-space-3-5); padding: var(--cy-space-3-5) 20px 0; border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); background: var(--cy-color-bg-surface); box-shadow: var(--cy-shadow-card); }
.cy-domain-page ::v-deep .el-table { overflow: hidden; border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); }
.cy-domain-page ::v-deep .el-table::before { display: none; }
.cy-domain-page ::v-deep .el-table th { background: #fafafd; color: var(--cy-color-text-secondary); }
.member-entity { display: flex; align-items: center; gap: var(--cy-space-1-5); border: 0; background: transparent; color: inherit; text-align: left; cursor: pointer; }
.member-entity strong, .member-entity small { display: block; }
.member-entity strong { color: var(--cy-color-brand); }
.member-entity small { margin-top: var(--cy-legacy-3); color: var(--cy-color-text-tertiary); }
.member-detail { padding: 0 24px var(--cy-space-7); }
.member-detail__hero { display: grid; grid-template-columns: 58px 1fr auto; align-items: center; gap: var(--cy-space-2-5); padding-bottom: 20px; border-bottom: 1px solid var(--cy-color-border-subtle); }
.member-detail__hero h2 { margin: 0; font-size: 20px; }
.member-detail__hero p { margin: var(--cy-legacy-5) 0 0; color: var(--cy-color-text-secondary); font-size: 13px; }
.member-detail__facts { display: grid; grid-template-columns: repeat(2, 1fr); gap: var(--cy-space-1-5); margin: var(--cy-space-3-5) 0; }
.member-detail__facts div { padding: var(--cy-space-2-5); border-radius: 6px; background: var(--cy-color-bg-subtle); }
.member-detail__facts dt { color: var(--cy-color-text-tertiary); font-size: 11px; }
.member-detail__facts dd { margin: var(--cy-space-0-5) 0 0; font-weight: 700; }
.member-detail__actions { padding: var(--cy-space-3-5); border: 1px solid var(--cy-color-border-subtle); border-radius: 8px; }
.member-detail__actions h3 { margin: 0; font-size: 15px; }
.member-detail__actions p { margin: 7px 0 var(--cy-space-2-5); color: var(--cy-color-text-secondary); font-size: 13px; line-height: 1.6; }
@media (max-width: 900px) { .member-overview { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 600px) { .member-overview, .member-detail__facts { grid-template-columns: 1fr; } .member-detail__hero { grid-template-columns: 58px 1fr; } }
</style>
