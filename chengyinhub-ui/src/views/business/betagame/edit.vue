<template>
  <div class="app-container">
    <el-card>
        <el-form ref="form" :model="form" :rules="rules" label-width="120px">
        <el-form-item label="游戏名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入游戏名称" style="width: 400px;" />
        </el-form-item>
         <el-form-item label="类型" prop="typeName">
          <el-input v-model="form.typeName" placeholder="请输入主题/活动" style="width: 400px;"/>
        </el-form-item>
        <el-form-item label="游戏标签" prop="tags">
          <el-input v-model="form.tags" placeholder="请输入游戏标签,英文逗号隔开" style="width: 400px;" />
        </el-form-item>
        <el-form-item label="游戏封面图" prop="imgUrl">
          <image-upload v-model="form.imgUrl" :limit="1"></image-upload>
        </el-form-item>
        <el-form-item label="游戏轮播图" prop="imgArr">
          <image-upload v-model="form.imgArr"/>
        </el-form-item>
        
        <el-form-item label="投票开始日期" prop="startDate">
          <el-date-picker clearable
            v-model="form.startDate"
            type="datetime"
            value-format="yyyy-MM-dd HH:mm:ss"
            placeholder="请选择投票开始日期">
          </el-date-picker>
        </el-form-item>
        <el-form-item label="投票结束日期" prop="endDate">
          <el-date-picker clearable
            v-model="form.endDate"
            type="datetime"
            value-format="yyyy-MM-dd HH:mm:ss"
            placeholder="请选择投票结束日期">
          </el-date-picker>
        </el-form-item>
        <el-form-item label="游戏描述">
          <editor v-model="form.description" :min-height="192"/>
        </el-form-item>
        
       
        <el-form-item label="审核状态：" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio
              v-for="dict in dict.type.sys_activity_publish_status"
              :key="dict.value"
              :label="parseInt(dict.value)"
            >{{dict.label}}</el-radio>
          </el-radio-group>
        </el-form-item>
        
        <el-form-item>
          <el-button type="primary" @click="submitForm">保存</el-button>
          <el-button @click="cancel">取消</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script>
import { listBetagame, getBetagame, delBetagame, addBetagame, updateBetagame } from "@/api/business/betagame"

export default {
  name: "BetagameEdit",
  dicts: ['sys_activity_publish_status'],
  data() {
    return {
      // 表单参数
      form: {
        id: null,
        name: null,
        description: null,
        imgUrl: null,
        imgArr: null,
        typeName: null,
        tags: null,
        status: 1,
        delFlag: null,
        startDate: null,
        endDate: null,
      },
      isEdit: false, // 新增这行
      loading: false, // 新增加载状态
      // 表单校验
      rules: {
        name: [
          { required: true, message: '请输入游戏名称', trigger: 'blur' },
        ],
        typeName: [
          { required: true, message: '请输入类型', trigger: 'blur' },
        ],
        startDate: [{ required: true, trigger: "blur", message: "请选择投票开始时间" }],
        endDate: [{ required: true, trigger: "blur", message: "请选择投票结束时间" }],
        imgUrl: [{ required: true, trigger: "blur", message: "请上传封面图片" }],
        imgArr: [{ required: true, trigger: "blur", message: "请上传轮播图片" }],
      }
    }
  },
  created() {
    this.initForm()
  },
 methods: {
    // 初始化表单
    initForm() {
      const id = this.$route.query.id
      const action = this.$route.query.action
      console.log('ID:', id);
      if (id) {
        this.isEdit = true
        this.loadBetaGameData(id)
      } else {
        this.isEdit = false
        this.$route.meta.title = '添加游戏'
      }
    },
    
    // 加载产品数据
    loadBetaGameData(id) {
      getBetagame(id).then(response => {
        this.form = response.data
        this.$route.meta.title = '修改游戏'
      }).catch(() => {
        this.$modal.msgError("获取信息失败")
        //this.$router.back()
        this.$router.back()
      })
    },
    
    // 提交表单
    submitForm() {
      this.$refs.form.validate(valid => {
        if (valid) {
          if (this.isEdit) {
            this.updateBetaGame()
          } else {
            this.addBetaGame()
          }
        }
      })
    },
    
    // 新增游戏
    addBetaGame() {
      addBetagame(this.form).then(response => {
        this.$modal.msgSuccess("新增成功");
        this.triggerRefreshAndClose();
      }).catch(error => {
        console.error('新增失败:', error);
        this.$modal.msgError('新增失败');
      });
    },
    
    // 修改游戏
    updateBetaGame() {
      updateBetagame(this.form).then(response => {
        this.$modal.msgSuccess("修改成功");
        this.triggerRefreshAndClose();
      }).catch(error => {
        console.error('修改失败:', error);
        this.$modal.msgError('修改失败');
      });
    },
    
    // 触发刷新并关闭页面
    triggerRefreshAndClose() {
      console.log('触发数据刷新事件');
      
      // 方式1：使用事件总线触发刷新
      this.$bus.$emit('betagame-refresh');
      
      // 方式2：也可以触发更具体的事件
    //   this.$bus.$emit('betagame-data-changed', {
    //     type: this.form.id ? 'update' : 'add',
    //     id: this.form.id
    //   });
      
      // 关闭当前页面
      this.closePage();
    },
    
    // 取消
    cancel() {
      // 取消时也可以选择是否触发刷新
      // this.$bus.$emit('betagame-refresh');
      this.closePage();
    },
    
    // 关闭页面
    closePage() {
      this.$tab.closeOpenPage({ path: '/business/betagame' });
    }
  }
}
</script>
