<template>
  <el-card shadow="never">
    <template #header>
      <div class="header-row">
        <div>
          <div class="title">管理员账号</div>
          <div class="subtitle">管理登录后台的账号、状态和密码。</div>
        </div>
        <el-button type="primary" @click="openCreate">新增管理员</el-button>
      </div>
    </template>
    <el-table :data="accounts" stripe v-loading="loading">
      <el-table-column prop="username" label="用户名" width="150" />
      <el-table-column prop="email" label="邮箱" min-width="210" />
      <el-table-column prop="nickname" label="昵称" width="150" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }"><el-tag :type="row.is_active ? 'success' : 'danger'">{{ row.is_active ? '启用' : '停用' }}</el-tag></template>
      </el-table-column>
      <el-table-column label="创建时间" width="180"><template #default="{ row }">{{ formatTime(row.created_at) }}</template></el-table-column>
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" @click="openPassword(row)">重置密码</el-button>
          <el-button size="small" type="danger" :disabled="row.id === currentAdminId" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新增管理员' : dialogMode === 'password' ? '重置密码' : '编辑管理员'" width="460px">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
      <template v-if="dialogMode === 'create'">
        <el-form-item label="用户名" prop="username"><el-input v-model="form.username" maxlength="32" /></el-form-item>
        <el-form-item label="邮箱" prop="email"><el-input v-model="form.email" /></el-form-item>
      </template>
      <el-form-item v-if="dialogMode !== 'password'" label="昵称" prop="nickname"><el-input v-model="form.nickname" maxlength="64" /></el-form-item>
      <el-form-item v-if="dialogMode === 'edit'" label="启用"><el-switch v-model="form.isActive" :disabled="form.id === currentAdminId" /></el-form-item>
      <el-form-item v-if="dialogMode !== 'edit'" label="新密码" prop="password"><el-input v-model="form.password" type="password" show-password autocomplete="new-password" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible=false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">确认</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../http.js'
import { getAdminUser } from '../authSession.js'

const accounts = ref([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const dialogMode = ref('create')
const formRef = ref()
const form = ref({ id: '', username: '', email: '', nickname: '', password: '', isActive: true })
const currentAdminId = getAdminUser().id
const rules = {
  username: [{ required: true, pattern: /^[A-Za-z0-9_]{2,32}$/, message: '请输入 2-32 位字母、数字或下划线' }],
  email: [{ required: true, type: 'email', message: '请输入有效邮箱' }],
  password: [{ required: true, min: 8, message: '密码至少 8 位' }],
}

async function load() {
  loading.value = true
  try { accounts.value = await http.get('/admin/accounts') }
  catch (error) { ElMessage.error(`管理员列表加载失败：${error}`) }
  finally { loading.value = false }
}

function blankForm() { return { id: '', username: '', email: '', nickname: '', password: '', isActive: true } }
function openCreate() { dialogMode.value = 'create'; form.value = blankForm(); dialogVisible.value = true }
function openEdit(row) { dialogMode.value = 'edit'; form.value = { ...blankForm(), id: row.id, nickname: row.nickname || '', isActive: row.is_active }; dialogVisible.value = true }
function openPassword(row) { dialogMode.value = 'password'; form.value = { ...blankForm(), id: row.id }; dialogVisible.value = true }

async function save() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (dialogMode.value === 'create') {
      await http.post('/admin/accounts', form.value)
    } else if (dialogMode.value === 'password') {
      await http.patch(`/admin/accounts/${form.value.id}`, { password: form.value.password })
    } else {
      await http.patch(`/admin/accounts/${form.value.id}`, { nickname: form.value.nickname, isActive: form.value.isActive })
    }
    dialogVisible.value = false
    ElMessage.success('管理员账号已保存')
    await load()
  } catch (error) {
    ElMessage.error(`保存失败：${error}`)
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`确认删除管理员 ${row.username}？该操作不可恢复。`, '删除管理员', { type: 'warning' })
    await http.delete(`/admin/accounts/${row.id}`)
    ElMessage.success('管理员已删除')
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(`删除失败：${error}`)
  }
}

function formatTime(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-' }
onMounted(load)
</script>

<style scoped>
.header-row { display:flex; align-items:center; justify-content:space-between; gap:16px; }
.title { font-weight:650; }
.subtitle { margin-top:4px; color:var(--miao-ink-secondary); font-size:12px; }
@media (max-width:640px) { .header-row { align-items:flex-start; flex-direction:column; } }
</style>
