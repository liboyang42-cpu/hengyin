<template>
  <div class="activity-detail-wrapper">
    <el-main v-loading="loading">
      <!-- 返回按钮 -->
      <!-- <div class="mb10">
        <el-button size="small" icon="el-icon-arrow-left" @click="$router.back()">返回</el-button>
      </div> -->
      
      <!-- 活动基本信息 -->
      <el-card class="mt10">
        <div slot="header" class="clearfix">
          <span style="font-size: 16px;font-weight: bold">活动基本信息</span>
        </div>
        <el-descriptions :column="2" border label-class-name="my-label" contentClassName="my-content" :label-style="LS">
          <el-descriptions-item   label="活动名称">{{ activityDetail.name }}</el-descriptions-item>
          <el-descriptions-item label="活动状态">
            <dict-tag :options="dict.type.sys_topic_status" :value="activityDetail.status" />
          </el-descriptions-item>
          <el-descriptions-item label="发布状态">
            <dict-tag :options="dict.type.sys_activity_publish_status" :value="activityDetail.publishStatus" />
          </el-descriptions-item>
          <el-descriptions-item label="发布时间">{{ activityDetail.createTime }}</el-descriptions-item>
          <el-descriptions-item label="发布人">{{ activityDetail.memberId }}</el-descriptions-item>
          <el-descriptions-item label="活动时间">
            {{ parseTime(activityDetail.startDate, '{y}-{m}-{d}') }} 至 {{ parseTime(activityDetail.endDate, '{y}-{m}-{d}') }}
          </el-descriptions-item>
          <el-descriptions-item label="场馆名称">{{ activityDetail.addressName }}</el-descriptions-item>
          <el-descriptions-item label="活动地址">{{ activityDetail.address }}</el-descriptions-item>
          <el-descriptions-item label="活动描述" :span="2">{{ activityDetail.description }}</el-descriptions-item>
        </el-descriptions>
        
        <!-- 活动封面图 -->
        <div class="activity-cover mt20">
          <div class="label">活动封面图：</div>
          <el-image 
            v-if="activityDetail.imgUrl" 
            :src="activityDetail.imgUrl" 
            :preview-src-list="[activityDetail.imgUrl]" 
            style="width: 200px; height: 120px;"
            fit="cover"
          ></el-image>
          <span v-else>无封面图</span>
        </div>
        
        <!-- 活动轮播图 -->
        <div class="activity-carousel mt20" v-if="imgList && imgList.length > 0">
          <div class="label">活动轮播图：</div>
          <div class="img-list">
            <el-image 
              v-for="(img, index) in imgList" 
              :key="index" 
              :src="img" 
              :preview-src-list="imgList" 
              style="width: 120px; height: 80px; margin-right: 10px;"
              fit="cover"
            ></el-image>
          </div>
        </div>
      </el-card>
      
      <!-- 阵容信息 -->
      <el-card class="mt10" v-if="lineupList && lineupList.length > 0">
        <div slot="header" class="clearfix">
          <span style="font-size: 16px;font-weight: bold">阵容</span>
        </div>
        <el-row :gutter="20">
          <el-col :span="8" v-for="(item, index) in lineupList" :key="index" class="mb20">
            <div class="lineup-item text-center">
              <el-avatar :size="80" :src="item.memberAvatar" class="mb10"></el-avatar>
              <div class="name">{{ item.memberRealName }}/{{ item.memberId }}</div>
              <div class="role">艺人</div>
              <!-- <div class="rating" v-if="item.rating">
                <el-rate v-model="item.rating" disabled show-score text-color="#ff9900" score-template="{value}"></el-rate>
              </div> -->
            </div>
          </el-col>
        </el-row>
      </el-card>
      
      <!-- 游戏介绍 -->
      <el-card class="mt10" v-if="gameList && gameList.length > 0">
        <div slot="header" class="clearfix">
          <span style="font-size: 16px;font-weight: bold">游戏介绍</span>
        </div>
        <el-collapse v-model="activeGameNames">
          <el-collapse-item 
            v-for="(game, index) in gameList" 
            :key="index" 
            :name="index"
            :title="game.title"
          >
            <div class="game-content">
              <p>{{ game.description }}</p>
            </div>
          </el-collapse-item>
        </el-collapse>
      </el-card>
      
      <!-- 票务信息：票务写操作只在所属活动详情内发起，顶级 ticket 页只做定位。 -->
      <el-card class="mt10">
        <div slot="header" class="clearfix activity-ticket-header">
          <span style="font-size: 16px;font-weight: bold">票务信息</span>
          <el-button
            type="primary"
            size="mini"
            icon="el-icon-plus"
            :disabled="!activityDetail.id"
            v-if="canAddTicket"
            v-hasPermi="['business:ticket:add']"
            @click="openAddTicket"
          >添加票务</el-button>
        </div>
        <el-table v-if="ticketList && ticketList.length > 0" :data="ticketList" border>
          <el-table-column label="票务名称" prop="name" align="center"></el-table-column>
          <el-table-column label="有效时间" align="center" width="220">
            <template slot-scope="{row}">
              {{ row.startTime }} 至 {{ row.endTime }}
            </template>
          </el-table-column>
          <el-table-column label="价格" prop="price" align="center" width="100">
            <template slot-scope="{row}">
              ¥{{ row.price }}
            </template>
          </el-table-column>
          <el-table-column label="库存" prop="totalInventory" align="center" width="80"></el-table-column>
          <el-table-column label="剩余" prop="remainingInventory" align="center" width="80"></el-table-column>
          <el-table-column label="操作" align="center" width="100">
            <template slot-scope="{row}">
              <el-button
                type="text"
                size="mini"
                v-if="canEditTicket"
                v-hasPermi="['business:ticket:edit']"
                @click="openEditTicket(row)"
              >编辑</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="当前活动暂无票务；可在此活动详情中添加。" />
      </el-card>
    </el-main>

    <el-dialog :title="ticketDialogTitle" :visible.sync="ticketDialogVisible" width="500px" append-to-body :close-on-click-modal="false">
      <el-form ref="ticketForm" :model="ticketForm" :rules="ticketRules" label-width="100px">
        <el-form-item label="票务名称" prop="name">
          <el-input v-model="ticketForm.name" placeholder="请输入票务名称" />
        </el-form-item>
        <el-form-item label="价格" prop="price">
          <el-input-number v-model="ticketForm.price" :precision="2" :min="0" :step="0.01" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="总库存" prop="totalInventory">
          <el-input-number v-model="ticketForm.totalInventory" :min="0" :step="1" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="开始时间" prop="startTime">
          <el-date-picker
            v-model="ticketForm.startTime"
            type="datetime"
            format="yyyy-MM-dd HH:mm:ss"
            value-format="yyyy-MM-dd HH:mm:ss"
            style="width: 100%;"
          />
        </el-form-item>
        <el-form-item label="结束时间" prop="endTime">
          <el-date-picker
            v-model="ticketForm.endTime"
            type="datetime"
            format="yyyy-MM-dd HH:mm:ss"
            value-format="yyyy-MM-dd HH:mm:ss"
            style="width: 100%;"
          />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button @click="ticketDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="ticketSaving" @click="submitTicketForm">保存</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { getActivity } from "@/api/business/activity"
import { addTicket, updateTicket } from "@/api/business/ticket"
import { parseTime } from "@/utils/ruoyi"

const emptyTicketForm = () => ({
  id: null,
  name: '',
  price: 0,
  totalInventory: 0,
  startTime: null,
  endTime: null
})

const isValidTicketTimeWindow = (startTime, endTime) => {
  if (!startTime || !endTime) return false
  return new Date(endTime) > new Date(startTime)
}

const hasAllPermissions = (permissions, required) =>
  permissions.includes('*:*:*') || required.every(permission => permissions.includes(permission))

export default {
  name: "ActivityDetail",
  dicts: ['sys_topic_status', 'sys_activity_publish_status'],
  data() {
    return {
      loading: false,
      activityDetail: {},
      lineupList: [], // 阵容列表
      gameList: [],   // 游戏介绍列表
      ticketList: [], // 票务信息列表
      activeGameNames: [0], // 默认展开第一个游戏介绍
      ticketDialogVisible: false,
      ticketDialogTitle: '',
      ticketSaving: false,
      ticketForm: emptyTicketForm(),
      ticketRules: {
        name: [{ required: true, message: '请输入票务名称', trigger: 'blur' }],
        price: [{ required: true, type: 'number', min: 0, message: '价格不能为负数', trigger: 'blur' }],
        totalInventory: [{ required: true, type: 'number', min: 0, message: '库存不能为负数', trigger: 'blur' }],
        startTime: [{ required: true, message: '请选择开始时间', trigger: 'change' }],
        endTime: [{ required: true, message: '请选择结束时间', trigger: 'change' }]
      },
      LS: {
        'text-align': 'left',
        'width': '120px',
        'word-break': 'keep-all'
      }
    }
  },
  computed: {
    canAddTicket() {
      const permissions = (this.$store && this.$store.getters && this.$store.getters.permissions) || []
      return hasAllPermissions(permissions, ['business:ticket:add', 'business:activity:edit'])
    },
    canEditTicket() {
      const permissions = (this.$store && this.$store.getters && this.$store.getters.permissions) || []
      return hasAllPermissions(permissions, ['business:ticket:edit', 'business:activity:edit'])
    },
    // 处理轮播图数据
    imgList() {
      if (this.activityDetail.imgArr) {
        return this.activityDetail.imgArr.split(';').filter(img => img.trim() !== '')
      }
      return []
    }
  },
  created() {
    const { id } = this.$route.query
    if (id) {
      this.getDetail(id)
    }
  },
  methods: {
    parseTime,
    // 获取活动详情
    getDetail(id) {
      this.loading = true
      return getActivity(id).then(res => {
        this.activityDetail = res.data
        // 这里根据实际API响应结构调整
        // 假设API返回的数据中包含lineupList, gameList, ticketList字段
        this.lineupList = res.data.cmsCollaboratorsList || []
        this.gameList = res.data.cmsMemberTemplateList || []
        this.ticketList = res.data.omsTicketList || []
      }).catch(() => {
        this.activityDetail = {}
        this.lineupList = []
        this.gameList = []
        this.ticketList = []
      }).finally(() => {
        this.loading = false
      })
    },
    openAddTicket() {
      if (!this.canAddTicket || !(this.activityDetail && this.activityDetail.id)) {
        this.$modal.msgError('没有添加该活动票务的权限')
        return
      }
      this.ticketForm = emptyTicketForm()
      this.ticketDialogTitle = '添加活动票务'
      this.ticketDialogVisible = true
      this.$nextTick(() => this.$refs.ticketForm && this.$refs.ticketForm.clearValidate())
    },
    openEditTicket(row) {
      if (!this.canEditTicket || !(this.activityDetail && this.activityDetail.id)) {
        this.$modal.msgError('没有编辑该活动票务的权限')
        return
      }
      const owned = this.findTicketOnCurrentActivity(row && row.id)
      if (!owned) {
        this.$modal.msgError('只能编辑当前活动下的票务')
        return
      }
      this.ticketForm = {
        id: owned.id,
        name: owned.name || '',
        price: Number(owned.price) || 0,
        totalInventory: Number(owned.totalInventory) || 0,
        startTime: owned.startTime || null,
        endTime: owned.endTime || null
      }
      this.ticketDialogTitle = '编辑活动票务'
      this.ticketDialogVisible = true
      this.$nextTick(() => this.$refs.ticketForm && this.$refs.ticketForm.clearValidate())
    },
    findTicketOnCurrentActivity(ticketId) {
      if (ticketId == null) return null
      return (this.ticketList || []).find(ticket => ticket && ticket.id === ticketId) || null
    },
    submitTicketForm() {
      this.$refs.ticketForm.validate(valid => {
        if (!valid || this.ticketSaving) return
        if (!isValidTicketTimeWindow(this.ticketForm.startTime, this.ticketForm.endTime)) {
          this.$modal.msgError('结束时间必须晚于开始时间')
          return
        }
        const form = Object.assign({}, this.ticketForm)
        const activityId = this.activityDetail && this.activityDetail.id
        if (!activityId) {
          this.$modal.msgError('活动信息缺失，无法保存票务')
          return
        }
        const isEditing = Boolean(form.id)
        if (isEditing ? !this.canEditTicket : !this.canAddTicket) {
          this.$modal.msgError(isEditing ? '没有编辑该活动票务的权限' : '没有添加该活动票务的权限')
          return
        }
        if (isEditing) {
          const owned = this.findTicketOnCurrentActivity(form.id)
          if (!owned) {
            this.$modal.msgError('只能编辑当前活动下的票务')
            return
          }
          form.id = owned.id
        }
        this.ticketSaving = true
        const payload = {
          id: form.id,
          ownerType: 2,
          ownerId: activityId,
          name: form.name,
          price: form.price,
          totalInventory: form.totalInventory,
          startTime: form.startTime,
          endTime: form.endTime
        }
        const request = isEditing
          ? updateTicket(payload)
          : addTicket(Object.assign(payload, {
            memberId: this.activityDetail.memberId,
            remainingInventory: form.totalInventory
          }))
        request.then(() => {
          this.$modal.msgSuccess(isEditing ? '票务已保存' : '票务已添加')
          this.ticketDialogVisible = false
          return this.getDetail(activityId)
        }).catch(() => {}).finally(() => {
          this.ticketSaving = false
        })
      })
    }
  }
}
</script>

<style lang="scss" scoped>
.activity-detail-wrapper {
  .label {
    font-weight: bold;
    margin-bottom: var(--cy-space-2);
    color: #606266;
  }
  
  .lineup-item {
    padding: var(--cy-legacy-15);
    border: 1px solid #ebeef5;
    border-radius: 4px;
    
    .name {
      font-weight: bold;
      margin-bottom: var(--cy-legacy-5);
    }
    
    .role {
      color: #909399;
      font-size: 13px;
      margin-bottom: var(--cy-space-1-5);
    }
  }
  
  .game-content {
    padding: 0 var(--cy-space-1-5);
    
    .feature-item {
      margin-bottom: var(--cy-legacy-5);
      color: #67c23a;
    }
  }
  
  .my-label {
    width: 100px;
  }
  
  .my-content {
    width: 400px;
  }
}

.activity-ticket-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
