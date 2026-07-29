<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import AdminShell from '../components/AdminShell.vue'
import {
  getDeadLetters,
  ignoreDeadLetter,
  retryDeadLetter,
  type DeadLetterRecord
} from '../api/admin'

const router = useRouter()
const records = ref<DeadLetterRecord[]>([])
const loading = ref(false)
const handlingId = ref<number | null>(null)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const status = ref('PENDING')

async function loadRecords() {
  try {
    loading.value = true
    const result = await getDeadLetters({
      page: page.value,
      size: size.value,
      status: status.value || undefined
    })
    records.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载死信记录失败')
  } finally {
    loading.value = false
  }
}

async function retry(record: DeadLetterRecord) {
  try {
    await ElMessageBox.confirm(
      `确认重新投递 ${record.messageType} 消息吗？业务处理必须具备幂等性。`,
      '重新投递死信',
      { type: 'warning' }
    )
    handlingId.value = record.id
    await retryDeadLetter(record.id)
    ElMessage.success('死信已重新投递')
    await loadRecords()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '死信重投失败')
  } finally {
    handlingId.value = null
  }
}

async function ignore(record: DeadLetterRecord) {
  try {
    await ElMessageBox.confirm('确认忽略该死信吗？忽略后不能再次重投。', '忽略死信')
    handlingId.value = record.id
    await ignoreDeadLetter(record.id)
    ElMessage.success('死信已忽略')
    await loadRecords()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '忽略死信失败')
  } finally {
    handlingId.value = null
  }
}

function formatDate(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN') : '-'
}

onMounted(loadRecords)
</script>

<template>
  <AdminShell
    title="RabbitMQ 死信处理"
    description="检查消费重试耗尽的异常消息，确认业务影响后进行安全重投或忽略。"
    eyebrow="系统运维"
  >
    <template #actions>
      <div class="heading-tools">
        <el-button @click="router.push('/admin/review')">返回审核</el-button>
        <el-button :loading="loading" @click="loadRecords">刷新记录</el-button>
      </div>
    </template>

    <template #overview>
      <section class="overview-grid">
        <article class="stat-card primary">
          <span class="stat-icon pending">待</span>
          <div>
            <small>当前筛选结果</small>
            <strong>{{ total }}</strong>
            <p>条死信记录</p>
          </div>
        </article>
        <article class="stat-card">
          <span class="stat-icon retried">重</span>
          <div>
            <small>本页已重投</small>
            <strong>{{ records.filter((record) => record.status === 'RETRIED').length }}</strong>
            <p>条处理记录</p>
          </div>
        </article>
        <article class="stat-card">
          <span class="stat-icon ignored">略</span>
          <div>
            <small>本页已忽略</small>
            <strong>{{ records.filter((record) => record.status === 'IGNORED').length }}</strong>
            <p>条处理记录</p>
          </div>
        </article>
      </section>
    </template>

    <section class="content-panel">
      <header class="panel-header">
        <div>
          <h2>死信记录</h2>
          <p>先核对失败原因与原始消息，再决定后续处理方式。</p>
        </div>
        <div class="filters">
          <span class="filter-label">处理状态</span>
          <el-select v-model="status" @change="loadRecords">
            <el-option label="待处理" value="PENDING" />
            <el-option label="已重投" value="RETRIED" />
            <el-option label="已忽略" value="IGNORED" />
            <el-option label="全部" value="" />
          </el-select>
        </div>
      </header>

      <div class="operation-notice">
        <span class="notice-icon">i</span>
        重投前请确认消费者具备幂等性，避免同一业务消息被重复执行。
      </div>

      <div class="table-wrap">
        <el-table :data="records" v-loading="loading" row-key="id">
          <el-table-column label="消息类型" width="165">
            <template #default="{ row }">
              <span class="message-type">{{ row.messageType }}</span>
            </template>
          </el-table-column>
          <el-table-column label="业务 ID" width="140">
            <template #default="{ row }">
              <span class="business-id">{{ row.businessId || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="queueName" label="死信队列" min-width="230" show-overflow-tooltip />
          <el-table-column label="失败原因" min-width="250">
            <template #default="{ row }">
              <p class="failure-reason">{{ row.failureReason || '-' }}</p>
            </template>
          </el-table-column>
          <el-table-column label="记录时间" width="180">
            <template #default="{ row }">{{ formatDate(row.createTime) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="105">
            <template #default="{ row }">
              <el-tag
                :type="row.status === 'PENDING' ? 'danger' : row.status === 'RETRIED' ? 'success' : 'info'"
                effect="light"
              >
                {{ row.status === 'PENDING' ? '待处理' : row.status === 'RETRIED' ? '已重投' : '已忽略' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="原始消息" width="100">
            <template #default="{ row }">
              <el-popover width="420" trigger="click">
                <div class="payload-head">
                  <strong>原始消息负载</strong>
                  <small>{{ row.messageType }}</small>
                </div>
                <pre class="payload">{{ row.payload }}</pre>
                <template #reference>
                  <el-button link type="primary">查看</el-button>
                </template>
              </el-popover>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <template v-if="row.status === 'PENDING'">
                <el-button
                  link
                  type="primary"
                  :loading="handlingId === row.id"
                  @click="retry(row)"
                >
                  重投
                </el-button>
                <el-button
                  link
                  type="danger"
                  :loading="handlingId === row.id"
                  @click="ignore(row)"
                >
                  忽略
                </el-button>
              </template>
              <span v-else class="handled-label">已处理</span>
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
          @current-change="loadRecords"
        />
      </div>
    </section>
  </AdminShell>
</template>

<style scoped>
.heading-tools,
.overview-grid,
.stat-card,
.panel-header,
.filters,
.operation-notice,
.payload-head {
  display: flex;
  align-items: center;
}

.heading-tools {
  gap: 10px;
}

.overview-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.stat-card {
  min-width: 0;
  gap: 14px;
  padding: 18px 20px;
  border: 1px solid #ebeef2;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 4px 16px rgb(0 0 0 / 2%);
}

.stat-card.primary {
  border-color: #ffe0e3;
  background: linear-gradient(125deg, #fff 48%, #fff4f5);
}

.stat-icon {
  display: grid;
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  place-items: center;
  border-radius: 12px;
}

.stat-icon.pending {
  background: #fff0f1;
  color: #f04f5f;
}

.stat-icon.retried {
  background: #eaf9f0;
  color: #2bb673;
}

.stat-icon.ignored {
  background: #eff1f4;
  color: #7b8088;
}

.stat-icon svg {
  width: 22px;
  height: 22px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.7;
}

.stat-card > div {
  display: grid;
  min-width: 0;
  grid-template-columns: auto auto;
  align-items: end;
  column-gap: 7px;
}

.stat-card small {
  grid-column: 1 / -1;
  color: #61666d;
  font-size: 12px;
}

.stat-card strong {
  margin-top: 3px;
  font-size: 25px;
  line-height: 1.05;
}

.stat-card p {
  overflow: hidden;
  margin: 0 0 1px;
  color: #9499a0;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
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

.filters {
  gap: 9px;
}

.filter-label {
  color: #9499a0;
  font-size: 12px;
}

.filters :deep(.el-select) {
  width: 145px;
}

.operation-notice {
  align-items: flex-start;
  gap: 9px;
  margin: 16px 20px 0;
  padding: 11px 13px;
  border: 1px solid #dceff7;
  border-radius: 9px;
  background: #f3fbff;
  color: #39748d;
  font-size: 12px;
  line-height: 1.6;
}

.notice-icon {
  display: grid;
  width: 19px;
  height: 19px;
  flex: 0 0 19px;
  place-items: center;
  border-radius: 50%;
  background: #00aeec;
  color: #fff;
  font-size: 11px;
  font-style: normal;
  font-weight: 700;
}

.table-wrap {
  overflow-x: auto;
  padding-top: 10px;
}

.table-wrap :deep(.el-table) {
  --el-table-header-bg-color: #fafbfc;
  --el-table-row-hover-bg-color: #f7fbfd;
  min-width: 1160px;
}

.table-wrap :deep(.el-table th.el-table__cell) {
  height: 46px;
  color: #61666d;
  font-size: 12px;
  font-weight: 600;
}

.table-wrap :deep(.el-table td.el-table__cell) {
  padding: 15px 0;
  color: #61666d;
  font-size: 12px;
}

.message-type {
  display: inline-block;
  padding: 5px 8px;
  border-radius: 6px;
  background: #e8f8ff;
  color: #168fc1;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 11px;
  font-weight: 650;
}

.business-id {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  color: #3d3f43;
}

.failure-reason {
  display: -webkit-box;
  overflow: hidden;
  margin: 0;
  color: #61666d;
  line-height: 1.55;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.handled-label {
  color: #b0b3b8;
  font-size: 11px;
}

.payload-head {
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 10px;
  padding-bottom: 9px;
  border-bottom: 1px solid #ebeef2;
}

.payload-head strong {
  color: #303236;
  font-size: 13px;
}

.payload-head small {
  color: #9499a0;
  font-size: 10px;
}

.payload {
  overflow: auto;
  max-height: 300px;
  margin: 0;
  padding: 12px;
  border-radius: 7px;
  background: #f6f8fa;
  color: #3d3f43;
  font-size: 11px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}

.pagination {
  display: flex;
  justify-content: center;
  padding: 20px;
  border-top: 1px solid #eef0f3;
}

@media (max-width: 850px) {
  .overview-grid {
    grid-template-columns: 1fr 1fr;
  }

  .stat-card:last-child {
    grid-column: 1 / -1;
  }
}

@media (max-width: 620px) {
  .heading-tools {
    justify-content: space-between;
  }

  .overview-grid {
    grid-template-columns: 1fr;
  }

  .stat-card:last-child {
    grid-column: auto;
  }

  .panel-header {
    align-items: stretch;
    flex-direction: column;
  }

  .filters {
    justify-content: space-between;
  }

  .filters :deep(.el-select) {
    width: 170px;
  }

  .operation-notice {
    margin: 13px 14px 0;
  }
}
</style>
