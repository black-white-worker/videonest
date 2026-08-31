<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AdminShell from '../components/AdminShell.vue'
import { getAdminUsers, getPasswordResetRequests, resetUserPassword, type AdminUser, type PasswordResetRequest } from '../api/admin'

const users = ref<AdminUser[]>([])
const requests = ref<PasswordResetRequest[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    ;[users.value, requests.value] = await Promise.all([getAdminUsers(), getPasswordResetRequests()])
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '获取用户信息失败')
  } finally {
    loading.value = false
  }
}

async function handleReset(item: PasswordResetRequest) {
  try {
    await ElMessageBox.confirm(`确定为用户 ${item.username} 初始化密码吗？临时密码只会显示一次。`, '重置密码', { type: 'warning' })
    const password = await resetUserPassword(item.id, item.userId)
    await ElMessageBox.alert(`临时密码：${password}\n请通过安全渠道交给用户，并提醒用户登录后立即修改。`, '密码已初始化', { confirmButtonText: '我已记录' })
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '重置密码失败')
  }
}

onMounted(load)
</script>

<template>
  <AdminShell title="用户管理" description="查看用户账号与用户 ID，处理密码重置申请">
    <template #actions><el-button :loading="loading" @click="load">刷新</el-button></template>
    <section class="panel">
      <div class="panel-title"><h2>用户账号</h2><span>仅展示账号信息，不展示密码</span></div>
      <el-table v-loading="loading" :data="users" stripe>
        <el-table-column prop="id" label="用户 ID" width="120" />
        <el-table-column prop="username" label="账号" />
        <el-table-column prop="nickname" label="昵称" />
        <el-table-column prop="role" label="角色" width="120" />
        <el-table-column label="状态" width="120"><template #default="{ row }">{{ row.status === 1 ? '正常' : '禁用' }}</template></el-table-column>
      </el-table>
    </section>
    <section class="panel">
      <div class="panel-title"><h2>密码重置申请</h2><span>管理员初始化后，通过安全渠道交付临时密码</span></div>
      <el-empty v-if="!requests.length" description="暂无待处理申请" />
      <el-table v-else :data="requests" stripe>
        <el-table-column prop="id" label="申请 ID" width="110" />
        <el-table-column prop="userId" label="用户 ID" width="110" />
        <el-table-column prop="username" label="账号" />
        <el-table-column prop="createTime" label="申请时间" />
        <el-table-column label="操作" width="140"><template #default="{ row }"><el-button type="primary" size="small" @click="handleReset(row)">初始化密码</el-button></template></el-table-column>
      </el-table>
    </section>
  </AdminShell>
</template>

<style scoped>
.panel { margin-bottom: 20px; padding: 22px; border: 1px solid #eef0f3; border-radius: 16px; background: #fff; box-shadow: 0 8px 24px rgb(24 25 28 / 4%); }
.panel-title { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 16px; }
.panel-title h2 { margin: 0; font-size: 18px; }
.panel-title span { color: #9499a0; font-size: 13px; }
</style>
