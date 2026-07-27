<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { deleteAdminComment, getAdminComments, restoreAdminComment, type AdminComment } from '../api/admin'

const router = useRouter()
const comments = ref<AdminComment[]>([])
const loading = ref(false)
const keyword = ref('')
const status = ref<'1' | '0' | ''>('1')
const page = ref(1)
const size = ref(20)
const total = ref(0)

function ensureAdmin() {
  try {
    const user = JSON.parse(localStorage.getItem('userInfo') || '{}')
    if (!localStorage.getItem('token') || user.role !== 'ADMIN') throw new Error()
    return true
  } catch {
    ElMessage.error('仅管理员可管理评论')
    router.replace('/login')
    return false
  }
}

async function loadComments() {
  try {
    loading.value = true
    const result = await getAdminComments({ page: page.value, size: size.value, keyword: keyword.value.trim() || undefined, status: status.value === '' ? undefined : Number(status.value) })
    comments.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '获取评论失败')
  } finally {
    loading.value = false
  }
}

async function removeComment(comment: AdminComment) {
  try {
    await ElMessageBox.confirm(`确定删除这条评论吗？${comment.parentId === '0' ? '其下回复也会一并删除。' : ''}`, '删除评论', { type: 'warning' })
    loading.value = true
    await deleteAdminComment(comment.id)
    ElMessage.success('评论已移入回收站')
    await loadComments()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '删除失败')
  } finally {
    loading.value = false
  }
}

async function restoreComment(comment: AdminComment) {
  try {
    await restoreAdminComment(comment.id)
    ElMessage.success('评论已恢复')
    await loadComments()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '恢复失败')
  }
}

function search() { page.value = 1; loadComments() }
function formatDate(value?: string) { return value ? new Date(value).toLocaleString('zh-CN') : '-' }
onMounted(() => { if (ensureAdmin()) loadComments() })
</script>

<template>
  <main class="page">
    <header><button @click="router.push('/')">VideoNest</button><el-button @click="router.push('/admin/review')">视频审核</el-button></header>
    <section>
      <div class="title"><div><h1>评论管理</h1><p>共 {{ total }} 条评论</p></div><div class="tools"><el-select v-model="status" @change="search"><el-option label="正常评论" value="1" /><el-option label="已删除评论" value="0" /><el-option label="全部评论" value="" /></el-select><el-input v-model="keyword" placeholder="评论、用户或视频标题" clearable @keyup.enter="search" /><el-button type="primary" @click="search">搜索</el-button></div></div>
      <el-table :data="comments" v-loading="loading" empty-text="暂无评论">
        <el-table-column label="评论内容" min-width="260"><template #default="{ row }"><p>{{ row.content }}</p><small>{{ row.parentId === '0' ? '一级评论' : '回复' }}</small></template></el-table-column>
        <el-table-column label="用户" width="150"><template #default="{ row }">{{ row.nickname }}<br><small>@{{ row.username }}</small></template></el-table-column>
        <el-table-column prop="videoTitle" label="视频" min-width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '正常' : '已删除' }}</el-tag></template></el-table-column>
        <el-table-column label="发布时间" width="180"><template #default="{ row }">{{ formatDate(row.createdAt) }}</template></el-table-column>
        <el-table-column label="删除时间" width="180"><template #default="{ row }">{{ formatDate(row.deletedAt) }}</template></el-table-column>
        <el-table-column label="操作" width="110"><template #default="{ row }"><el-button v-if="row.status === 1" link type="danger" @click="removeComment(row)">删除</el-button><el-button v-else link type="primary" @click="restoreComment(row)">恢复</el-button></template></el-table-column>
      </el-table>
      <div v-if="total > size" class="pagination"><el-pagination v-model:current-page="page" :page-size="size" :total="total" layout="prev, pager, next" background @current-change="loadComments" /></div>
    </section>
  </main>
</template>

<style scoped>
.page{min-height:100vh;background:#f6f7f8;color:#18191c}.page header{height:64px;display:flex;align-items:center;justify-content:space-between;padding:0 max(24px,calc((100% - 1100px)/2));background:#fff}.page header button{border:0;background:none;color:#1677ff;font-size:22px;font-weight:700;cursor:pointer}section{width:min(1240px,calc(100% - 48px));margin:0 auto;padding:32px 0}.title,.tools{display:flex;align-items:center;gap:10px}.title{justify-content:space-between;margin-bottom:20px}h1{margin:0 0 6px;font-size:25px}p,small{margin:0;color:#9499a0}.tools{width:560px}.tools .el-select{width:140px}.pagination{display:flex;justify-content:center;margin-top:24px}
</style>
