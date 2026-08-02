<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  ElMessage,
  ElMessageBox,
  type FormInstance,
  type FormRules
} from 'element-plus'
import { useRouter } from 'vue-router'
import {
  deleteCreatorVideo,
  getMyFavoriteVideos,
  getMyLikedVideos,
  getCreatorProfile,
  getCreatorVideos,
  updateCreatorVideo,
  type CreatorProfile,
  type CreatorVideo
} from '../api/creator'
import { getCategories, type VideoCategory, type VideoListItem } from '../api/video'
import {
  getMyFollowers,
  getMyFollowing,
  unfollowUser,
  type FollowUser
} from '../api/follow'
import SiteHeader from '../components/SiteHeader.vue'

const router = useRouter()

const profile = ref<CreatorProfile | null>(null)
const videos = ref<CreatorVideo[]>([])
const categories = ref<VideoCategory[]>([])

const profileLoading = ref(false)
const videoLoading = ref(false)
const editLoading = ref(false)
const followLoading = ref(false)
const followTab = ref<'following' | 'followers'>('following')
const followUsers = ref<FollowUser[]>([])
const followPage = ref(1)
const followSize = ref(10)
const followTotal = ref(0)
const interactionTab = ref<'favorites' | 'likes'>('favorites')
const interactionVideos = ref<VideoListItem[]>([])
const interactionLoading = ref(false)
const interactionPage = ref(1)
const interactionSize = ref(8)
const interactionTotal = ref(0)
const activeCenterSection = ref<'submissions' | 'interactions' | 'follows'>('submissions')
const profileCoverUrl = 'https://images.unsplash.com/photo-1511497584788-876760111969?auto=format&fit=crop&w=2200&q=88'

const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

const editDialogVisible = ref(false)
const editFormRef = ref<FormInstance>()
const editingVideoId = ref<number | null>(null)

const editForm = reactive({
  title: '',
  description: '',
  categoryId: undefined as number | undefined,
  coverObjectName: '',
  videoObjectName: '',
  duration: 0
})

const editRules: FormRules = {
  title: [
    {
      required: true,
      message: '请输入视频标题',
      trigger: 'blur'
    },
    {
      max: 100,
      message: '标题不能超过 100 个字符',
      trigger: 'blur'
    }
  ],
  categoryId: [
    {
      required: true,
      message: '请选择视频分区',
      trigger: 'change'
    }
  ],
  description: [
    {
      max: 2000,
      message: '简介不能超过 2000 个字符',
      trigger: 'blur'
    }
  ]
}

function ensureLoggedIn() {
  if (localStorage.getItem('token')) {
    return true
  }

  ElMessage.warning('请先登录')
  router.replace('/login')
  return false
}

async function loadProfile() {
  try {
    profileLoading.value = true
    profile.value = await getCreatorProfile()
  } catch (error) {
    ElMessage.error(
      error instanceof Error ? error.message : '获取个人信息失败'
    )
  } finally {
    profileLoading.value = false
  }
}

async function loadVideos() {
  try {
    videoLoading.value = true

    const result = await getCreatorVideos({
      page: currentPage.value,
      size: pageSize.value
    })

    videos.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(
      error instanceof Error ? error.message : '获取我的投稿失败'
    )
  } finally {
    videoLoading.value = false
  }
}

async function loadCategories() {
  try {
    categories.value = await getCategories()
  } catch {
    ElMessage.error('获取视频分区失败')
  }
}

async function loadFollowUsers() {
  try {
    followLoading.value = true
    const request = followTab.value === 'following' ? getMyFollowing : getMyFollowers
    const result = await request({ page: followPage.value, size: followSize.value })
    followUsers.value = result.records
    followTotal.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '获取关注列表失败')
  } finally {
    followLoading.value = false
  }
}

async function loadInteractionVideos() {
  try {
    interactionLoading.value = true
    const request = interactionTab.value === 'favorites' ? getMyFavoriteVideos : getMyLikedVideos
    const result = await request({ page: interactionPage.value, size: interactionSize.value })
    interactionVideos.value = result.records
    interactionTotal.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '获取互动视频失败')
  } finally {
    interactionLoading.value = false
  }
}

function changeInteractionTab(tab: 'favorites' | 'likes') {
  interactionTab.value = tab
  interactionPage.value = 1
  loadInteractionVideos()
}

function changeFollowTab(tab: 'following' | 'followers') {
  followTab.value = tab
  followPage.value = 1
  loadFollowUsers()
}

async function removeFollowing(user: FollowUser) {
  try {
    followLoading.value = true
    await unfollowUser(user.id)
    ElMessage.success(`已取消关注 ${user.nickname}`)
    await loadFollowUsers()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '取消关注失败')
  } finally {
    followLoading.value = false
  }
}

function handlePageChange(page: number) {
  currentPage.value = page
  loadVideos()
}

function getStatusText(status: CreatorVideo['status']) {
  const map = {
    PROCESSING: '转码中',
    PROCESS_FAILED: '转码失败',
    PENDING: '审核中',
    PUBLISHED: '已发布',
    REJECTED: '已驳回'
  }

  return map[status]
}

function getStatusType(status: CreatorVideo['status']) {
  const map = {
    PROCESSING: 'info',
    PROCESS_FAILED: 'danger',
    PENDING: 'warning',
    PUBLISHED: 'success',
    REJECTED: 'danger'
  } as const

  return map[status]
}

function formatDate(value?: string) {
  if (!value) {
    return '-'
  }

  return new Date(value).toLocaleString('zh-CN')
}

function formatDuration(seconds: number) {
  const minutes = Math.floor(seconds / 60)
  const remainSeconds = seconds % 60

  return `${String(minutes).padStart(2, '0')}:${String(
    remainSeconds
  ).padStart(2, '0')}`
}

function openEditDialog(video: CreatorVideo) {
  editingVideoId.value = video.id

  editForm.title = video.title
  editForm.description = video.description || ''
  editForm.categoryId = video.categoryId
  editForm.coverObjectName = video.coverObjectName
  editForm.videoObjectName = video.videoObjectName
  editForm.duration = video.duration

  editDialogVisible.value = true
}

async function submitEdit() {
  if (!editFormRef.value || !editingVideoId.value) {
    return
  }

  const valid = await editFormRef.value.validate().catch(() => false)

  if (!valid) {
    return
  }

  try {
    editLoading.value = true

    await updateCreatorVideo(editingVideoId.value, {
      title: editForm.title.trim(),
      description: editForm.description.trim(),
      categoryId: editForm.categoryId!,
      coverObjectName: editForm.coverObjectName,
      videoObjectName: editForm.videoObjectName,
      duration: editForm.duration
    })

    ElMessage.success('视频信息已更新')
    editDialogVisible.value = false

    await Promise.all([loadVideos(), loadProfile()])
  } catch (error) {
    ElMessage.error(
      error instanceof Error ? error.message : '更新视频失败'
    )
  } finally {
    editLoading.value = false
  }
}

async function handleDelete(video: CreatorVideo) {
  try {
    await ElMessageBox.confirm(
      `确定将《${video.title}》移入回收站吗？到期后视频和封面资源将自动永久清理。`,
      '删除视频',
      {
        type: 'warning',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消'
      }
    )

    await deleteCreatorVideo(video.id)

    ElMessage.success('视频已移入回收站')

    // 当前页只有一条数据且不是第一页时，自动返回上一页
    if (videos.value.length === 1 && currentPage.value > 1) {
      currentPage.value -= 1
    }

    await Promise.all([loadVideos(), loadProfile()])
  } catch (error) {
    // 点击取消不提示错误
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(
        error instanceof Error ? error.message : '删除视频失败'
      )
    }
  }
}

function logout() {
  localStorage.removeItem('token')
  localStorage.removeItem('userInfo')

  ElMessage.success('已退出登录')
  router.push('/login')
}

onMounted(() => {
  if (!ensureLoggedIn()) {
    return
  }

  loadProfile()
  loadVideos()
  loadCategories()
  loadFollowUsers()
  loadInteractionVideos()
})
</script>

<template>
  <main class="profile-page">
    <SiteHeader max-width="1280px">
      <template #nav>
        <span class="center-label">个人中心</span>
      </template>
      <template #actions>
        <el-button text @click="router.push('/notifications')">消息</el-button>
        <el-button @click="router.push('/')">返回主站</el-button>
        <el-button type="primary" @click="router.push('/upload')">+ 投稿</el-button>
        <el-button text type="danger" @click="logout">退出</el-button>
      </template>
    </SiteHeader>

    <section class="container">
      <el-skeleton :loading="profileLoading" animated>
        <template #default>
          <section
            v-if="profile"
            class="profile-card profile-space-banner"
            :style="{ '--profile-cover': `url(${profileCoverUrl})` }"
          >
            <div class="avatar">
              {{ profile.nickname.slice(0, 1).toUpperCase() }}
            </div>

            <div class="profile-info">
              <h1>{{ profile.nickname }}</h1>
              <p>@{{ profile.username }}</p>

              <el-tag :type="profile.role === 'ADMIN' ? 'warning' : 'info'">
                {{ profile.role === 'ADMIN' ? '管理员' : '普通用户' }}
              </el-tag>
            </div>

            <div class="stats">
              <div>
                <strong>{{ profile.totalVideoCount }}</strong>
                <span>全部投稿</span>
              </div>

              <div>
                <strong>{{ profile.pendingVideoCount }}</strong>
                <span>审核中</span>
              </div>

              <div>
                <strong>{{ profile.publishedVideoCount }}</strong>
                <span>已发布</span>
              </div>

              <div>
                <strong>{{ profile.rejectedVideoCount }}</strong>
                <span>已驳回</span>
              </div>
            </div>
          </section>
        </template>
      </el-skeleton>

      <div class="profile-workspace">
        <aside class="center-sidebar">
          <div class="center-sidebar__title">我的空间</div>
          <button
            :class="{ active: activeCenterSection === 'submissions' }"
            @click="activeCenterSection = 'submissions'"
          >
            <span>▣</span>
            <div><strong>投稿管理</strong><small>{{ total }} 个稿件</small></div>
          </button>
          <button
            :class="{ active: activeCenterSection === 'interactions' }"
            @click="activeCenterSection = 'interactions'"
          >
            <span>★</span>
            <div><strong>收藏与点赞</strong><small>管理互动内容</small></div>
          </button>
          <button
            :class="{ active: activeCenterSection === 'follows' }"
            @click="activeCenterSection = 'follows'"
          >
            <span>◎</span>
            <div><strong>关注与粉丝</strong><small>管理社交关系</small></div>
          </button>
          <div class="sidebar-create">
            <strong>分享你的新灵感</strong>
            <p>上传视频，和社区一起发现精彩。</p>
            <el-button type="primary" @click="router.push('/upload')">发布视频</el-button>
          </div>
        </aside>

        <div class="center-content">
      <section v-show="activeCenterSection === 'follows'" class="follow-section">
        <div class="section-title">
          <div>
            <h2>我的关注</h2>
            <p>管理你关注的创作者和粉丝</p>
          </div>
          <el-button :loading="followLoading" @click="loadFollowUsers">刷新</el-button>
        </div>

        <el-tabs
          :model-value="followTab"
          @tab-change="changeFollowTab($event as 'following' | 'followers')"
        >
          <el-tab-pane label="我的关注" name="following" />
          <el-tab-pane label="我的粉丝" name="followers" />
        </el-tabs>

        <el-skeleton :loading="followLoading" animated :rows="3">
          <template #default>
            <div v-if="followUsers.length" class="follow-list">
              <article v-for="user in followUsers" :key="user.id" class="follow-user">
                <div class="follow-avatar">{{ user.nickname.slice(0, 1).toUpperCase() }}</div>
                <div class="follow-user-info">
                  <strong>{{ user.nickname }}</strong>
                  <span>@{{ user.username }} · {{ formatDate(user.followedAt) }}</span>
                </div>
                <el-button
                  v-if="followTab === 'following'"
                  plain
                  @click="removeFollowing(user)"
                >取消关注</el-button>
              </article>
            </div>
            <el-empty v-else :description="followTab === 'following' ? '你还没有关注任何用户' : '暂时还没有粉丝'" />
          </template>
        </el-skeleton>

        <div v-if="followTotal > followSize" class="pagination">
          <el-pagination
            v-model:current-page="followPage"
            :page-size="followSize"
            :total="followTotal"
            layout="prev, pager, next"
            background
            @current-change="loadFollowUsers"
          />
        </div>
      </section>

      <section v-show="activeCenterSection === 'interactions'" class="interaction-section">
        <div class="section-title">
          <div>
            <h2>我的互动</h2>
            <p>查看你收藏和点赞过的已发布视频</p>
          </div>
          <el-button :loading="interactionLoading" @click="loadInteractionVideos">刷新</el-button>
        </div>

        <el-tabs :model-value="interactionTab" @tab-change="changeInteractionTab($event as 'favorites' | 'likes')">
          <el-tab-pane label="我的收藏" name="favorites" />
          <el-tab-pane label="我的点赞" name="likes" />
        </el-tabs>

        <el-skeleton :loading="interactionLoading" animated :count="4">
          <template #default>
            <div v-if="interactionVideos.length" class="interaction-grid">
              <article v-for="video in interactionVideos" :key="video.id" class="interaction-card" @click="router.push(`/video/${video.id}`)">
                <div class="interaction-cover"><img :src="video.coverUrl" :alt="video.title" loading="lazy"><span>{{ formatDuration(video.duration) }}</span></div>
                <h3 :title="video.title">{{ video.title }}</h3>
                <p>{{ video.authorNickname }} · {{ video.categoryName }}</p>
              </article>
            </div>
            <el-empty v-else :description="interactionTab === 'favorites' ? '你还没有收藏视频' : '你还没有点赞视频'" />
          </template>
        </el-skeleton>
        <div v-if="interactionTotal > interactionSize" class="pagination">
          <el-pagination v-model:current-page="interactionPage" :page-size="interactionSize" :total="interactionTotal" layout="prev, pager, next" background @current-change="loadInteractionVideos" />
        </div>
      </section>

      <section v-show="activeCenterSection === 'submissions'" class="submission-section">
        <div class="section-title">
          <div>
            <h2>我的投稿</h2>
            <p>共 {{ total }} 个视频投稿</p>
          </div>

          <el-button :loading="videoLoading" @click="loadVideos">
            刷新
          </el-button>
        </div>

        <el-skeleton :loading="videoLoading" animated :count="4">
          <template #default>
            <div v-if="videos.length > 0" class="video-list">
              <article
                v-for="video in videos"
                :key="video.id"
                class="video-card"
              >
                <div class="cover-box">
                   <img
                     v-if="video.coverUrl"
                     :src="video.coverUrl"
                     :alt="video.title"
                     loading="lazy"
                   />
                  <span v-else class="processing-cover">处理中</span>

                  <span class="duration">
                    {{ formatDuration(video.duration) }}
                  </span>
                </div>

                <div class="video-content">
                  <div class="title-row">
                    <h3>{{ video.title }}</h3>

                    <el-tag :type="getStatusType(video.status)">
                      {{ getStatusText(video.status) }}
                    </el-tag>
                    <el-tag
                      v-if="video.reviewTimeoutNotified === 1"
                      type="danger"
                    >
                      审核已超时
                    </el-tag>
                  </div>

                  <p class="description">
                    {{ video.description || '暂无视频简介。' }}
                  </p>

                  <div class="meta">
                    <span>分区：{{ video.categoryName }}</span>
                    <span>投稿时间：{{ formatDate(video.createTime) }}</span>

                    <span v-if="video.status === 'PUBLISHED'">
                      播放：{{ video.viewCount }}
                    </span>
                  </div>

                  <div
                    v-if="video.status === 'REJECTED' && video.rejectReason"
                    class="reject-reason"
                  >
                    <strong>驳回原因：</strong>
                    {{ video.rejectReason }}
                  </div>

                  <div
                    v-if="video.status === 'PROCESS_FAILED' && video.processError"
                    class="reject-reason"
                  >
                    <strong>转码失败：</strong>
                    {{ video.processError }}
                  </div>

                  <div
                    v-if="video.reviewTimeoutNotified === 1"
                    class="reject-reason"
                  >
                    <strong>审核提醒：</strong>
                    等待审核已超时，管理员会尽快处理。
                  </div>

                  <div class="video-actions">
                    <el-button
                      v-if="video.status === 'PUBLISHED'"
                      type="primary"
                      plain
                      size="small"
                      @click="router.push(`/video/${video.id}`)"
                    >
                      查看视频
                    </el-button>

                    <el-button size="small" @click="openEditDialog(video)">
                      编辑信息
                    </el-button>

                    <el-button
                      type="danger"
                      plain
                      size="small"
                      @click="handleDelete(video)"
                    >
                      删除
                    </el-button>
                  </div>
                </div>
              </article>
            </div>

            <el-empty
              v-else
              description="你还没有投稿，去发布第一个视频吧"
            >
              <el-button type="primary" @click="router.push('/upload')">
                去投稿
              </el-button>
            </el-empty>
          </template>
        </el-skeleton>

        <div v-if="total > pageSize" class="pagination">
          <el-pagination
            v-model:current-page="currentPage"
            :page-size="pageSize"
            :total="total"
            layout="prev, pager, next"
            background
            @current-change="handlePageChange"
          />
        </div>
      </section>
        </div>
      </div>
    </section>

    <el-dialog
      v-model="editDialogVisible"
      title="编辑视频信息"
      width="520px"
      destroy-on-close
    >
      <el-form
        ref="editFormRef"
        :model="editForm"
        :rules="editRules"
        label-position="top"
      >
        <el-form-item label="视频标题" prop="title">
          <el-input
            v-model="editForm.title"
            maxlength="100"
            show-word-limit
          />
        </el-form-item>

        <el-form-item label="视频分区" prop="categoryId">
          <el-select
            v-model="editForm.categoryId"
            placeholder="请选择视频分区"
            class="full-width"
          >
            <el-option
              v-for="category in categories"
              :key="category.id"
              :label="category.name"
              :value="category.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="视频简介" prop="description">
          <el-input
            v-model="editForm.description"
            type="textarea"
            :rows="5"
            maxlength="2000"
            show-word-limit
            placeholder="请输入视频简介"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="editDialogVisible = false">
          取消
        </el-button>

        <el-button
          type="primary"
          :loading="editLoading"
          @click="submitEdit"
        >
          保存修改
        </el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.profile-page {
  min-height: 100vh;
  background: #f6f7f8;
  color: #18191c;
}

.header {
  height: 64px;
  background: #fff;
  border-bottom: 1px solid #e7e7e7;
}

.header-content,
.container {
  width: min(1100px, calc(100% - 48px));
  margin: 0 auto;
}

.header-content {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  border: 0;
  background: transparent;
  color: #1677ff;
  font-size: 22px;
  font-weight: 700;
  cursor: pointer;
}

.logo span {
  display: inline-grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border-radius: 50%;
  background: #1677ff;
  color: #fff;
  font-size: 13px;
}

.header-actions {
  display: flex;
  gap: 10px;
}

.container {
  padding: 30px 0 48px;
}

.profile-card {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 28px;
  border-radius: 12px;
  background: #fff;
}

.avatar {
  display: grid;
  width: 68px;
  height: 68px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 50%;
  background: #1677ff;
  color: #fff;
  font-size: 28px;
  font-weight: 600;
}

.profile-info h1 {
  margin: 0 0 7px;
  font-size: 23px;
}

.profile-info p {
  margin: 0 0 9px;
  color: #9499a0;
  font-size: 14px;
}

.stats {
  display: flex;
  margin-left: auto;
  gap: 38px;
}

.stats div {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 7px;
}

.stats strong {
  font-size: 22px;
}

.stats span {
  color: #7a7f87;
  font-size: 13px;
}

.submission-section {
  margin-top: 24px;
  padding: 26px;
  border-radius: 12px;
  background: #fff;
}

.follow-section {
  margin-top: 24px;
  padding: 26px;
  border-radius: 12px;
  background: #fff;
}

.interaction-section {
  margin-top: 24px;
  padding: 26px;
  border-radius: 12px;
  background: #fff;
}

.interaction-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 18px;
}

.interaction-card { min-width: 0; cursor: pointer; }
.interaction-cover { position: relative; overflow: hidden; aspect-ratio: 16 / 9; border-radius: 8px; background: #e5e7eb; }
.interaction-cover img { width: 100%; height: 100%; object-fit: cover; transition: transform .25s; }
.interaction-card:hover img { transform: scale(1.05); }
.interaction-cover span { position: absolute; right: 6px; bottom: 6px; padding: 2px 5px; border-radius: 4px; background: rgb(0 0 0 / 65%); color: #fff; font-size: 12px; }
.interaction-card h3 { overflow: hidden; margin: 9px 0 5px; font-size: 15px; white-space: nowrap; text-overflow: ellipsis; }
.interaction-card p { overflow: hidden; margin: 0; color: #9499a0; font-size: 13px; white-space: nowrap; text-overflow: ellipsis; }

.follow-list {
  display: flex;
  flex-direction: column;
}

.follow-user {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 0;
  border-bottom: 1px solid #f0f0f0;
}

.follow-user:last-child {
  border-bottom: 0;
}

.follow-avatar {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border-radius: 50%;
  background: #e8f3ff;
  color: #1677ff;
  font-weight: 600;
}

.follow-user-info {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.follow-user-info span {
  overflow: hidden;
  color: #9499a0;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 22px;
}

.section-title h2 {
  margin: 0 0 7px;
  font-size: 21px;
}

.section-title p {
  margin: 0;
  color: #9499a0;
  font-size: 14px;
}

.video-list {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.video-card {
  display: grid;
  grid-template-columns: 220px 1fr;
  gap: 18px;
  padding-bottom: 18px;
  border-bottom: 1px solid #f0f0f0;
}

.video-card:last-child {
  padding-bottom: 0;
  border-bottom: 0;
}

.cover-box {
  position: relative;
  overflow: hidden;
  aspect-ratio: 16 / 9;
  border-radius: 8px;
  background: #e5e7eb;
}

.cover-box img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.processing-cover {
  display: grid;
  width: 100%;
  height: 100%;
  place-items: center;
  color: #7a7f87;
  font-size: 14px;
}

.duration {
  position: absolute;
  right: 7px;
  bottom: 7px;
  padding: 2px 5px;
  border-radius: 4px;
  background: rgb(0 0 0 / 65%);
  color: #fff;
  font-size: 12px;
}

.video-content {
  min-width: 0;
}

.title-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.title-row h3 {
  overflow: hidden;
  margin: 0;
  font-size: 17px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.description {
  display: -webkit-box;
  overflow: hidden;
  margin: 10px 0;
  color: #61666d;
  font-size: 14px;
  line-height: 21px;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  color: #9499a0;
  font-size: 13px;
}

.reject-reason {
  margin-top: 13px;
  padding: 10px 12px;
  border-radius: 6px;
  background: #fff2f0;
  color: #cf1322;
  font-size: 14px;
  line-height: 22px;
}

.video-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 13px;
}

.full-width {
  width: 100%;
}

.pagination {
  display: flex;
  justify-content: center;
  margin-top: 28px;
}

@media (max-width: 720px) {
  .header-content,
  .container {
    width: min(100% - 28px, 1100px);
  }

  .header-actions .el-button:first-child {
    display: none;
  }

  .profile-card {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .stats {
    width: 100%;
    justify-content: space-between;
    margin: 10px 0 0;
    gap: 8px;
  }

  .submission-section {
    padding: 20px;
  }

  .interaction-section { padding: 20px; }
  .interaction-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px 12px; }

  .video-card {
    grid-template-columns: 1fr;
  }
}
</style>

<style scoped>
.profile-page {
  min-height: 100vh;
  background: var(--vn-page);
}

.center-label {
  padding-left: 16px;
  border-left: 1px solid var(--vn-border);
  color: var(--vn-text-secondary);
  font-size: 14px;
  font-weight: 600;
}

.container {
  width: min(1280px, calc(100% - 48px));
  margin: 0 auto;
  padding: 28px 0 72px;
}

.profile-card {
  position: relative;
  overflow: hidden;
  min-height: 190px;
  margin-bottom: 22px;
  padding: 30px 34px;
  display: grid;
  grid-template-columns: auto minmax(180px, 1fr) auto;
  align-items: center;
  gap: 20px;
  border: 1px solid var(--vn-border-light);
  border-radius: 16px;
  background:
    radial-gradient(circle at 82% 10%, rgb(251 114 153 / 20%), transparent 28%),
    radial-gradient(circle at 12% 0%, rgb(94 216 255 / 28%), transparent 32%),
    linear-gradient(135deg, #fff, #f4fbfe);
  box-shadow: none;
}

.profile-card::after {
  content: "VIDEONEST SPACE";
  position: absolute;
  right: 28px;
  bottom: 14px;
  color: rgb(0 174 236 / 8%);
  font-size: 28px;
  font-weight: 900;
  letter-spacing: 2px;
}

.profile-card .avatar {
  width: 88px;
  height: 88px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 4px solid #fff;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--vn-primary), #67dcff);
  box-shadow: 0 10px 24px rgb(0 174 236 / 22%);
  color: #fff;
  font-size: 32px;
  font-weight: 800;
}

.profile-info h1 {
  margin: 0;
  font-size: 26px;
}

.profile-info p {
  margin: 5px 0 10px;
  color: var(--vn-text-muted);
  font-size: 13px;
}

.stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(74px, 1fr));
  gap: 8px;
}

.stats > div {
  min-width: 76px;
  padding: 12px 10px;
  border: 1px solid rgb(255 255 255 / 65%);
  border-radius: 10px;
  background: rgb(255 255 255 / 68%);
  text-align: center;
  backdrop-filter: blur(8px);
}

.stats strong,
.stats span {
  display: block;
}

.stats strong {
  color: var(--vn-text);
  font-size: 20px;
}

.stats span {
  margin-top: 2px;
  color: var(--vn-text-muted);
  font-size: 11px;
}

.profile-workspace {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  align-items: start;
  gap: 20px;
}

.center-sidebar {
  position: sticky;
  top: 88px;
  padding: 16px;
  border: 1px solid var(--vn-border-light);
  border-radius: 13px;
  background: #fff;
}

.center-sidebar__title {
  padding: 5px 9px 12px;
  color: var(--vn-text-muted);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 1px;
}

.center-sidebar > button {
  width: 100%;
  margin-bottom: 5px;
  padding: 11px 10px;
  display: flex;
  align-items: center;
  gap: 10px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--vn-text-secondary);
  text-align: left;
  cursor: pointer;
  transition: .2s;
}

.center-sidebar > button:hover,
.center-sidebar > button.active {
  background: var(--vn-primary-soft);
  color: var(--vn-primary-dark);
}

.center-sidebar > button > span {
  width: 26px;
  height: 26px;
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border-radius: 7px;
  background: #f1f2f3;
}

.center-sidebar > button.active > span {
  background: var(--vn-primary);
  color: #fff;
}

.center-sidebar strong,
.center-sidebar small {
  display: block;
}

.center-sidebar strong {
  font-size: 13px;
}

.center-sidebar small {
  margin-top: 2px;
  color: var(--vn-text-muted);
  font-size: 10px;
}

.sidebar-create {
  margin-top: 14px;
  padding: 14px;
  border-radius: 10px;
  background: linear-gradient(135deg, #eefaff, #fff2f6);
}

.sidebar-create > strong {
  color: var(--vn-text);
  font-size: 12px;
}

.sidebar-create p {
  margin: 6px 0 10px;
  color: var(--vn-text-muted);
  font-size: 10px;
  line-height: 1.6;
}

.sidebar-create .el-button {
  width: 100%;
}

.center-content {
  min-width: 0;
}

.follow-section,
.interaction-section,
.submission-section {
  margin: 0;
  padding: 26px 28px;
  border: 1px solid var(--vn-border-light);
  border-radius: 13px;
  background: #fff;
  box-shadow: none;
}

.section-title {
  margin-bottom: 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.section-title h2 {
  margin: 0 0 4px;
  font-size: 20px;
}

.section-title p {
  margin: 0;
  color: var(--vn-text-muted);
  font-size: 12px;
}

.follow-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.follow-user {
  padding: 14px;
  border: 1px solid var(--vn-border-light);
  border-radius: 10px;
  background: #fafbfc;
}

.follow-avatar {
  background: linear-gradient(135deg, var(--vn-primary), #6bdcff);
}

.interaction-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 22px 14px;
}

.interaction-card {
  padding: 0;
  border: 0;
  background: transparent;
}

.interaction-cover {
  border-radius: 9px;
}

.interaction-card h3 {
  display: -webkit-box;
  min-height: 40px;
  margin: 9px 0 4px;
  overflow: hidden;
  font-size: 14px;
  line-height: 20px;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.interaction-card p {
  color: var(--vn-text-muted);
  font-size: 11px;
}

.submission-section .video-list {
  border: 0;
  border-radius: 0;
  box-shadow: none;
}

.submission-section .video-card {
  margin-bottom: 12px;
  padding: 14px;
  border: 1px solid var(--vn-border-light);
  border-radius: 11px;
  background: #fafbfc;
  transition: border-color .2s, transform .2s;
}

.submission-section .video-card:hover {
  border-color: #bceafb;
  transform: translateY(-1px);
}

.reject-reason {
  border: 0;
  border-left: 3px solid #f56c6c;
  border-radius: 6px;
}

.pagination {
  display: flex;
  justify-content: center;
  margin-top: 24px;
}

@media (max-width: 1000px) {
  .profile-card {
    grid-template-columns: auto 1fr;
  }

  .stats {
    grid-column: 1 / -1;
  }

  .profile-workspace {
    grid-template-columns: 1fr;
  }

  .center-sidebar {
    position: static;
    display: flex;
    overflow-x: auto;
    gap: 6px;
  }

  .center-sidebar__title,
  .sidebar-create {
    display: none;
  }

  .center-sidebar > button {
    min-width: 150px;
    margin: 0;
  }

  .interaction-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 700px) {
  .container {
    width: min(100% - 24px, 1280px);
    padding-top: 18px;
  }

  .profile-card {
    padding: 24px 18px;
    grid-template-columns: 1fr;
    text-align: center;
  }

  .profile-card .avatar {
    margin: 0 auto;
  }

  .stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .follow-section,
  .interaction-section,
  .submission-section {
    padding: 20px 16px;
  }

  .follow-list {
    grid-template-columns: 1fr;
  }

  .interaction-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .submission-section .video-card {
    grid-template-columns: 1fr;
  }
}
</style>

<style scoped>
/* 个人空间参考内容站主页：横幅身份区 + 横向内容导航。 */
.profile-space-banner {
  min-height: 270px;
  padding: 128px 34px 28px;
  align-items: end;
  border: 0;
  background-color: #25324a;
  background-image: var(--profile-cover);
  background-position: center 48%;
  background-size: cover;
  box-shadow: 0 12px 30px rgb(15 23 42 / 14%);
  color: #fff;
}

.profile-space-banner::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    linear-gradient(180deg, rgb(4 10 24 / 4%) 20%, rgb(4 10 24 / 78%) 100%),
    linear-gradient(90deg, rgb(4 10 24 / 28%), transparent 64%);
}

.profile-space-banner::after {
  z-index: 1;
  color: rgb(255 255 255 / 14%);
}

.profile-space-banner > * {
  position: relative;
  z-index: 2;
}

.profile-space-banner .avatar {
  width: 98px;
  height: 98px;
  border-color: rgb(255 255 255 / 92%);
  background: linear-gradient(135deg, #00aeec, #78e4ff);
  box-shadow: 0 10px 28px rgb(0 0 0 / 24%);
}

.profile-space-banner .profile-info h1 {
  color: #fff;
  text-shadow: 0 2px 12px rgb(0 0 0 / 38%);
}

.profile-space-banner .profile-info p {
  color: rgb(255 255 255 / 76%);
}

.profile-space-banner .stats > div {
  border-color: rgb(255 255 255 / 18%);
  background: rgb(8 15 30 / 44%);
  backdrop-filter: blur(14px);
}

.profile-space-banner .stats strong {
  color: #fff;
}

.profile-space-banner .stats span {
  color: rgb(255 255 255 / 68%);
}

.profile-workspace {
  grid-template-columns: 1fr;
  gap: 16px;
}

.center-sidebar {
  position: sticky;
  z-index: 8;
  top: 80px;
  padding: 10px 12px;
  display: flex;
  align-items: center;
  overflow-x: auto;
  gap: 6px;
  border-radius: 12px;
  box-shadow: 0 5px 18px rgb(15 23 42 / 5%);
  scrollbar-width: none;
}

.center-sidebar__title {
  display: none;
}

.center-sidebar > button {
  width: auto;
  min-width: 164px;
  margin: 0;
  padding: 9px 12px;
  flex: 0 0 auto;
}

.center-sidebar > button.active {
  background: #dff5fd;
  color: #075985;
}

.center-sidebar > button.active > span {
  background: #00aeec;
  color: #111827;
  font-weight: 900;
}

.sidebar-create {
  min-width: 250px;
  margin: 0 0 0 auto;
  padding: 10px 12px;
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: center;
  gap: 0 10px;
}

.sidebar-create p {
  margin: 2px 0 0;
}

.sidebar-create .el-button {
  width: auto;
  grid-row: 1 / 3;
  grid-column: 2;
  color: #111827;
  font-weight: 800;
}

.center-content {
  width: 100%;
}

@media (max-width: 1000px) {
  .profile-space-banner {
    grid-template-columns: auto 1fr;
  }

  .center-sidebar {
    position: sticky;
  }

  .sidebar-create {
    display: none;
  }
}

@media (max-width: 700px) {
  .profile-space-banner {
    min-height: 330px;
    padding: 110px 18px 22px;
    grid-template-columns: 1fr;
    background-position: center;
  }

  .center-sidebar {
    top: 68px;
  }

  .center-sidebar > button {
    min-width: 150px;
  }
}
</style>
