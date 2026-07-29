<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import AdminShell from '../components/AdminShell.vue'
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
function goBack() {
  if (window.history.length > 1) {
    router.back()
    return
  }
  router.push('/')
}
function formatDate(value?: string) { return value ? new Date(value).toLocaleString('zh-CN') : '-' }
onMounted(() => { if (ensureAdmin()) loadComments() })
</script>

<template>
  <AdminShell
    title="评论管理"
    description="查看全站评论与回复，快速定位不当内容，并支持删除记录的恢复管理。"
    eyebrow="社区治理"
  >
    <template #actions>
      <div class="heading-tools">
        <el-button @click="goBack">返回上一页</el-button>
        <el-button :loading="loading" @click="loadComments">刷新数据</el-button>
      </div>
    </template>

    <template #overview>
      <section class="overview-strip">
        <div class="overview-main">
          <span class="overview-icon">评</span>
          <span>
            <small>当前筛选结果</small>
            <strong>{{ total }}</strong>
            <em>条评论</em>
          </span>
        </div>
        <div class="overview-detail">
          <span>
            <small>内容范围</small>
            <strong>{{ status === '1' ? '正常评论' : status === '0' ? '已删除评论' : '全部评论' }}</strong>
          </span>
          <span>
            <small>当前页码</small>
            <strong>第 {{ page }} 页</strong>
          </span>
          <span>
            <small>单页数量</small>
            <strong>{{ size }} 条</strong>
          </span>
        </div>
      </section>
    </template>

    <section class="content-panel">
      <header class="panel-header">
        <div>
          <h2>评论列表</h2>
          <p>支持按评论内容、用户或视频标题搜索。</p>
        </div>
        <div class="tools">
          <el-select v-model="status" @change="search">
            <el-option label="正常评论" value="1" />
            <el-option label="已删除评论" value="0" />
            <el-option label="全部评论" value="" />
          </el-select>
          <el-input
            v-model="keyword"
            placeholder="评论、用户或视频标题"
            clearable
            @keyup.enter="search"
          />
          <el-button type="primary" @click="search">搜索</el-button>
        </div>
      </header>

      <div class="table-wrap">
        <el-table
          :data="comments"
          v-loading="loading"
          empty-text="暂无评论"
          row-key="id"
        >
          <el-table-column label="评论内容" min-width="300">
            <template #default="{ row }">
              <div class="comment-cell">
                <span class="comment-level" :class="{ reply: row.parentId !== '0' }">
                  {{ row.parentId === '0' ? '评' : '回' }}
                </span>
                <div>
                  <p>{{ row.content }}</p>
                  <small>{{ row.parentId === '0' ? '一级评论' : '回复' }}</small>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="用户" width="170">
            <template #default="{ row }">
              <div class="user-cell">
                <span class="user-avatar">{{ row.nickname.slice(0, 1) }}</span>
                <span>
                  <strong>{{ row.nickname }}</strong>
                  <small>@{{ row.username }}</small>
                </span>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="videoTitle" label="所属视频" min-width="190" show-overflow-tooltip />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'info'" effect="light">
                {{ row.status === 1 ? '正常' : '已删除' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="发布时间" width="180">
            <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="删除时间" width="180">
            <template #default="{ row }">{{ formatDate(row.deletedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.status === 1"
                link
                type="danger"
                @click="removeComment(row)"
              >
                删除
              </el-button>
              <el-button v-else link type="primary" @click="restoreComment(row)">
                恢复
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div v-if="total > size" class="pagination">
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="prev, pager, next"
          background
          @current-change="loadComments"
        />
      </div>
    </section>
  </AdminShell>
</template>

<style scoped>
.heading-tools,
.overview-strip,
.overview-main,
.overview-detail,
.panel-header,
.tools,
.comment-cell,
.user-cell {
  display: flex;
  align-items: center;
}

.heading-tools {
  gap: 10px;
}

.overview-strip {
  justify-content: space-between;
  gap: 28px;
  margin-bottom: 18px;
  padding: 18px 22px;
  border: 1px solid #dceff7;
  border-radius: 13px;
  background:
    linear-gradient(110deg, #fff 40%, rgb(232 248 255 / 72%)),
    #fff;
  box-shadow: 0 5px 18px rgb(0 0 0 / 2%);
}

.overview-main {
  gap: 14px;
}

.overview-icon {
  display: grid;
  width: 46px;
  height: 46px;
  place-items: center;
  border-radius: 13px;
  background: #dff6ff;
  color: #00aeec;
}

.overview-icon svg {
  width: 23px;
  height: 23px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.65;
}

.overview-main > span:last-child {
  display: grid;
  grid-template-columns: auto auto;
  align-items: end;
  column-gap: 7px;
}

.overview-main small {
  grid-column: 1 / -1;
  color: #61666d;
  font-size: 12px;
}

.overview-main strong {
  color: #18191c;
  font-size: 26px;
  line-height: 1.1;
}

.overview-main em {
  padding-bottom: 2px;
  color: #9499a0;
  font-size: 12px;
  font-style: normal;
}

.overview-detail {
  gap: 0;
}

.overview-detail > span {
  display: flex;
  min-width: 126px;
  flex-direction: column;
  gap: 4px;
  padding: 0 22px;
  border-left: 1px solid #e5e7eb;
}

.overview-detail small {
  color: #9499a0;
  font-size: 11px;
}

.overview-detail strong {
  color: #3d3f43;
  font-size: 13px;
  font-weight: 600;
}

.content-panel {
  overflow: hidden;
  border: 1px solid #ebeef2;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 6px 24px rgb(0 0 0 / 3%);
}

.panel-header {
  justify-content: space-between;
  gap: 24px;
  padding: 19px 21px;
  border-bottom: 1px solid #eef0f3;
}

.panel-header h2 {
  margin: 0;
  font-size: 17px;
}

.panel-header p {
  margin: 5px 0 0;
  color: #9499a0;
  font-size: 12px;
}

.tools {
  width: min(590px, 58%);
  justify-content: flex-end;
  gap: 9px;
}

.tools :deep(.el-select) {
  width: 145px;
  flex: 0 0 145px;
}

.tools :deep(.el-input) {
  max-width: 310px;
}

.tools :deep(.el-button--primary) {
  --el-button-bg-color: #00aeec;
  --el-button-border-color: #00aeec;
  --el-button-hover-bg-color: #27b9ed;
  --el-button-hover-border-color: #27b9ed;
}

.table-wrap {
  overflow-x: auto;
}

.table-wrap :deep(.el-table) {
  --el-table-header-bg-color: #fafbfc;
  --el-table-row-hover-bg-color: #f7fbfd;
  min-width: 1050px;
}

.table-wrap :deep(.el-table th.el-table__cell) {
  height: 46px;
  color: #61666d;
  font-size: 12px;
  font-weight: 600;
}

.table-wrap :deep(.el-table td.el-table__cell) {
  padding: 14px 0;
}

.comment-cell {
  align-items: flex-start;
  gap: 11px;
}

.comment-level {
  display: grid;
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  place-items: center;
  border-radius: 8px;
  background: #e8f8ff;
  color: #00aeec;
  font-size: 12px;
  font-weight: 700;
}

.comment-level.reply {
  background: #f2efff;
  color: #7367dc;
}

.comment-cell > div {
  min-width: 0;
}

.comment-cell p {
  display: -webkit-box;
  overflow: hidden;
  margin: 0 0 5px;
  color: #303236;
  font-size: 13px;
  line-height: 1.55;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.comment-cell small,
.user-cell small {
  color: #9499a0;
  font-size: 11px;
}

.user-cell {
  gap: 9px;
}

.user-avatar {
  display: grid;
  width: 31px;
  height: 31px;
  flex: 0 0 31px;
  place-items: center;
  border-radius: 50%;
  background: linear-gradient(145deg, #dff6ff, #c9efff);
  color: #168fc1;
  font-size: 12px;
  font-weight: 700;
}

.user-cell > span:last-child {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.user-cell strong {
  overflow: hidden;
  color: #303236;
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pagination {
  display: flex;
  justify-content: center;
  padding: 20px;
  border-top: 1px solid #eef0f3;
}

@media (max-width: 1120px) {
  .overview-detail > span {
    min-width: 106px;
    padding: 0 15px;
  }

  .tools {
    width: 62%;
  }
}

@media (max-width: 760px) {
  .heading-tools {
    justify-content: space-between;
  }

  .overview-strip {
    align-items: flex-start;
    flex-direction: column;
    gap: 17px;
  }

  .overview-detail {
    width: 100%;
  }

  .overview-detail > span {
    min-width: 0;
    flex: 1;
    padding: 0 12px;
  }

  .overview-detail > span:first-child {
    padding-left: 0;
    border-left: 0;
  }

  .panel-header {
    align-items: stretch;
    flex-direction: column;
  }

  .tools {
    width: 100%;
  }

  .tools :deep(.el-input) {
    max-width: none;
  }
}

@media (max-width: 520px) {
  .overview-detail > span:last-child {
    display: none;
  }

  .panel-header {
    padding: 17px;
  }

  .tools {
    display: grid;
    grid-template-columns: 1fr auto;
  }

  .tools :deep(.el-select) {
    width: 100%;
    grid-column: 1 / -1;
  }
}
</style>
