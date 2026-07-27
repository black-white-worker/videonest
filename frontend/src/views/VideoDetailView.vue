<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  createComment,
  deleteComment,
  favoriteVideo,
  getComments,
  getCommentReplies,
  getInteractionStatus,
  getVideoDetail,
  likeVideo,
  unfavoriteVideo,
  unlikeVideo,
  type VideoComment,
  type VideoDetail
} from '../api/video'
import { followUser, getFollowStatus, unfollowUser } from '../api/follow'

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const video = ref<VideoDetail | null>(null)
const player = ref<HTMLVideoElement | null>(null)
const selectedQuality = ref<'480p' | '720p' | '1080p'>('720p')
const liked = ref(false)
const favorited = ref(false)
const interactionLoading = ref(false)
const followLoading = ref(false)
const followed = ref(false)
const comments = ref<VideoComment[]>([])
const commentContent = ref('')
const commentLoading = ref(false)
const commentSubmitting = ref(false)
const commentPage = ref(1)
const commentSize = ref(20)
const commentTotal = ref(0)
const replyLoadingIds = ref<string[]>([])
const expandedReplyIds = ref<string[]>([])
const repliesByCommentId = ref<Record<string, VideoComment[]>>({})
const replyContents = ref<Record<string, string>>({})
const replySubmittingIds = ref<string[]>([])

const qualityOptions = computed(() => {
  if (!video.value) return []

  return [
    { label: '480P', value: '480p' as const, url: video.value.video480pUrl },
    { label: '720P', value: '720p' as const, url: video.value.video720pUrl || video.value.videoUrl },
    { label: '1080P', value: '1080p' as const, url: video.value.video1080pUrl }
  ]
})

const currentVideoUrl = computed(() =>
  qualityOptions.value.find(option => option.value === selectedQuality.value)?.url
    || video.value?.videoUrl
    || ''
)

function formatNumber(value: number) {
  if (value >= 10000) {
    return `${(value / 10000).toFixed(1)}万`
  }

  return value.toString()
}

function formatDate(value: string) {
  if (!value) {
    return ''
  }

  return new Date(value).toLocaleString('zh-CN')
}

async function loadVideoDetail() {
  const id = Number(route.params.id)

  if (!Number.isInteger(id) || id <= 0) {
    ElMessage.error('视频 ID 不合法')
    router.push('/')
    return
  }

  try {
    loading.value = true
    video.value = await getVideoDetail(id)
    selectedQuality.value = video.value.video720pUrl
      ? '720p'
      : qualityOptions.value[0]?.value || '720p'
  } catch (error) {
    const message = error instanceof Error ? error.message : '获取视频详情失败'
    ElMessage.error(message)
    router.push('/')
    return
  } finally {
    loading.value = false
  }

  // 点赞、关注和评论加载失败不应影响播放器打开。
  await Promise.allSettled([
    loadInteractionStatus(id),
    loadFollowStatus(),
    loadComments(id)
  ])
}

async function changeQuality() {
  const currentTime = player.value?.currentTime || 0
  const wasPlaying = player.value ? !player.value.paused : false

  await nextTick()
  if (!player.value) return

  player.value.currentTime = currentTime
  if (wasPlaying) {
    player.value.play().catch(() => undefined)
  }
}

function isMyVideo(): boolean {
  const userInfo = localStorage.getItem('userInfo')
  if (!video.value || !userInfo) return false

  try {
    return JSON.parse(userInfo).userId === video.value.authorId
  } catch {
    return false
  }
}

async function loadFollowStatus() {
  if (!video.value || !localStorage.getItem('token') || isMyVideo()) return
  const status = await getFollowStatus(video.value.authorId)
  followed.value = status.followed
}

async function toggleFollow() {
  if (!video.value || !requireLogin() || isMyVideo()) return

  try {
    followLoading.value = true
    if (followed.value) {
      await unfollowUser(video.value.authorId)
    } else {
      await followUser(video.value.authorId)
    }
    followed.value = !followed.value
    ElMessage.success(followed.value ? '已关注' : '已取消关注')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '关注操作失败')
  } finally {
    followLoading.value = false
  }
}

async function loadComments(videoId: number) {
  try {
    commentLoading.value = true
    const result = await getComments(
      videoId,
      commentPage.value,
      commentSize.value
    )
    comments.value = result.records
    commentTotal.value = result.total
  } catch (error) {
    const message = error instanceof Error ? error.message : '获取评论失败'
    ElMessage.error(message)
  } finally {
    commentLoading.value = false
  }
}

async function loadInteractionStatus(videoId: number) {
  if (!localStorage.getItem('token')) {
    return
  }

  const status = await getInteractionStatus(videoId)
  liked.value = status.liked
  favorited.value = status.favorited

  if (video.value) {
    video.value.likeCount = status.likeCount
    video.value.favoriteCount = status.favoriteCount
  }
}

function requireLogin(): boolean {
  if (localStorage.getItem('token')) {
    return true
  }

  ElMessage.warning('请先登录后再操作')
  router.push('/login')
  return false
}

async function toggleLike() {
  if (!video.value || !requireLogin()) {
    return
  }

  try {
    interactionLoading.value = true

    if (liked.value) {
      await unlikeVideo(video.value.id)
    } else {
      await likeVideo(video.value.id)
    }

    await loadInteractionStatus(video.value.id)
  } catch (error) {
    const message = error instanceof Error ? error.message : '点赞操作失败'
    ElMessage.error(message)
  } finally {
    interactionLoading.value = false
  }
}

async function toggleFavorite() {
  if (!video.value || !requireLogin()) {
    return
  }

  try {
    interactionLoading.value = true

    if (favorited.value) {
      await unfavoriteVideo(video.value.id)
    } else {
      await favoriteVideo(video.value.id)
    }

    await loadInteractionStatus(video.value.id)
  } catch (error) {
    const message = error instanceof Error ? error.message : '收藏操作失败'
    ElMessage.error(message)
  } finally {
    interactionLoading.value = false
  }
}

async function submitComment() {
  if (!video.value || !requireLogin()) {
    return
  }

  const content = commentContent.value.trim()

  if (!content) {
    ElMessage.warning('请输入评论内容')
    return
  }

  try {
    commentSubmitting.value = true
    await createComment(video.value.id, content)
    commentContent.value = ''
    commentPage.value = 1
    await loadComments(video.value.id)
    ElMessage.success('评论发布成功')
  } catch (error) {
    const message = error instanceof Error ? error.message : '评论发布失败'
    ElMessage.error(message)
  } finally {
    commentSubmitting.value = false
  }
}

async function removeComment(commentId: string) {
  if (!video.value || !requireLogin()) {
    return
  }

  try {
    commentLoading.value = true
    await deleteComment(video.value.id, commentId)
    await loadComments(video.value.id)
    ElMessage.success('评论已删除')
  } catch (error) {
    const message = error instanceof Error ? error.message : '删除评论失败'
    ElMessage.error(message)
  } finally {
    commentLoading.value = false
  }
}

function isReplyExpanded(commentId: string) {
  return expandedReplyIds.value.includes(commentId)
}

async function toggleReplies(comment: VideoComment) {
  if (isReplyExpanded(comment.id)) {
    expandedReplyIds.value = expandedReplyIds.value.filter(id => id !== comment.id)
    return
  }
  try {
    replyLoadingIds.value.push(comment.id)
    const result = await getCommentReplies(video.value!.id, comment.id)
    repliesByCommentId.value[comment.id] = result.records
    expandedReplyIds.value.push(comment.id)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '获取回复失败')
  } finally {
    replyLoadingIds.value = replyLoadingIds.value.filter(id => id !== comment.id)
  }
}

async function submitReply(comment: VideoComment) {
  if (!video.value || !requireLogin()) return
  const content = (replyContents.value[comment.id] || '').trim()
  if (!content) return ElMessage.warning('请输入回复内容')
  try {
    replySubmittingIds.value.push(comment.id)
    await createComment(video.value.id, content, comment.id)
    replyContents.value[comment.id] = ''
    const result = await getCommentReplies(video.value.id, comment.id)
    repliesByCommentId.value[comment.id] = result.records
    if (!isReplyExpanded(comment.id)) expandedReplyIds.value.push(comment.id)
    comment.replyCount = (comment.replyCount || 0) + 1
    ElMessage.success('回复发布成功')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '回复发布失败')
  } finally {
    replySubmittingIds.value = replySubmittingIds.value.filter(id => id !== comment.id)
  }
}

function changeCommentPage(page: number) {
  if (!video.value) {
    return
  }

  commentPage.value = page
  loadComments(video.value.id)
}

function isMyComment(comment: VideoComment): boolean {
  const userInfo = localStorage.getItem('userInfo')

  if (!userInfo) {
    return false
  }

  try {
    return JSON.parse(userInfo).userId === comment.userId
  } catch {
    return false
  }
}

function canDeleteComment(comment: VideoComment): boolean {
  const userInfo = localStorage.getItem('userInfo')

  if (!userInfo) {
    return false
  }

  try {
    const currentUser = JSON.parse(userInfo)
    return currentUser.userId === comment.userId ||
      (
        String(currentUser.role).trim().toUpperCase() === 'ADMIN' &&
        String(comment.parentId) === '0'
      )
  } catch {
    return false
  }
}

function goHome() {
  router.push('/')
}

onMounted(() => {
  loadVideoDetail()
})
</script>

<template>
  <main class="detail-page">
    <header class="header">
      <div class="header-content">
        <div class="logo" @click="goHome">
          <span class="logo-icon">▶</span>
          <span>VideoNest</span>
        </div>

        <el-button @click="goHome">返回首页</el-button>
      </div>
    </header>

    <section class="container">
      <el-skeleton :loading="loading" animated :rows="12">
        <template #default>
          <template v-if="video">
            <div class="video-player-box">
              <div v-if="qualityOptions.length" class="quality-switcher">
                <span>清晰度</span>
                <el-radio-group v-model="selectedQuality" size="small" @change="changeQuality">
                  <el-radio-button
                    v-for="option in qualityOptions"
                    :key="option.value"
                    :value="option.value"
                    :disabled="!option.url"
                  >
                    {{ option.label }}
                  </el-radio-button>
                </el-radio-group>
              </div>
              <video
                ref="player"
                :poster="video.coverUrl"
                :src="currentVideoUrl"
                controls
                class="video-player"
              >
                当前浏览器不支持视频播放。
              </video>
            </div>

            <section class="video-info">
              <div class="category">
                {{ video.categoryName }}
              </div>

              <h1>{{ video.title }}</h1>

              <div class="statistics">
                <span>{{ formatNumber(video.viewCount) }} 播放</span>
                <span>{{ formatNumber(video.likeCount) }} 点赞</span>
                <span>{{ formatNumber(video.favoriteCount) }} 收藏</span>
                <span>{{ formatDate(video.publishTime) }}</span>
              </div>

              <div class="interaction-actions">
                <el-button
                  :type="liked ? 'primary' : 'default'"
                  :loading="interactionLoading"
                  @click="toggleLike"
                >
                  {{ liked ? '已点赞' : '点赞' }}
                </el-button>

                <el-button
                  :type="favorited ? 'warning' : 'default'"
                  :loading="interactionLoading"
                  @click="toggleFavorite"
                >
                  {{ favorited ? '已收藏' : '收藏' }}
                </el-button>
              </div>

              <div class="author">
                <div class="avatar">
                  {{ video.authorNickname.slice(0, 1) }}
                </div>

                <div>
                  <strong>{{ video.authorNickname }}</strong>
                  <p>@{{ video.authorUsername }}</p>
                </div>

                <el-button
                  v-if="!isMyVideo()"
                  :type="followed ? 'default' : 'primary'"
                  plain
                  :loading="followLoading"
                  @click="toggleFollow"
                >
                  {{ followed ? '已关注' : '关注' }}
                </el-button>
              </div>

              <el-divider />

              <div class="description">
                <h3>视频简介</h3>
                <p>{{ video.description || '该视频暂未填写简介。' }}</p>
              </div>
            </section>

            <section class="comment-section">
              <div class="comment-header">
                <h2>评论</h2>
                <span>{{ commentTotal }} 条评论</span>
              </div>

              <div class="comment-editor">
                <el-input
                  v-model="commentContent"
                  type="textarea"
                  :rows="3"
                  maxlength="500"
                  show-word-limit
                  placeholder="说点什么吧..."
                />
                <el-button
                  type="primary"
                  :loading="commentSubmitting"
                  @click="submitComment"
                >
                  发布评论
                </el-button>
              </div>

              <el-skeleton :loading="commentLoading" animated :rows="4">
                <template #default>
                  <div v-if="comments.length" class="comment-list">
                    <article
                      v-for="comment in comments"
                      :key="comment.id"
                      class="comment-item"
                    >
                      <div class="comment-avatar">
                        {{ comment.nickname.slice(0, 1) }}
                      </div>
                      <div class="comment-main">
                        <div class="comment-name">{{ comment.nickname }}</div>
                        <p>{{ comment.content }}</p>
                        <div class="comment-meta">
                          <span>{{ formatDate(comment.createdAt) }}</span>
                          <el-button link @click="toggleReplies(comment)">
                            {{ isReplyExpanded(comment.id) ? '收起回复' : `回复${comment.replyCount ? ` (${comment.replyCount})` : ''}` }}
                          </el-button>
                          <el-button
                            v-if="canDeleteComment(comment)"
                            link
                            type="danger"
                            @click="removeComment(comment.id)"
                          >
                            删除
                          </el-button>
                        </div>
                        <div v-if="isReplyExpanded(comment.id)" class="reply-area">
                          <el-skeleton :loading="replyLoadingIds.includes(comment.id)" animated :rows="2">
                            <template #default>
                              <div v-for="reply in repliesByCommentId[comment.id] || []" :key="reply.id" class="reply-item">
                                <strong>{{ reply.nickname }}</strong>：{{ reply.content }}
                                <el-button v-if="isMyComment(reply)" link type="danger" size="small" @click="removeComment(reply.id)">删除</el-button>
                              </div>
                              <el-empty v-if="!(repliesByCommentId[comment.id] || []).length" description="暂无回复" :image-size="55" />
                            </template>
                          </el-skeleton>
                          <div class="reply-editor">
                            <el-input v-model="replyContents[comment.id]" maxlength="500" placeholder="写下你的回复" @keyup.enter="submitReply(comment)" />
                            <el-button type="primary" :loading="replySubmittingIds.includes(comment.id)" @click="submitReply(comment)">回复</el-button>
                          </div>
                        </div>
                      </div>
                    </article>
                  </div>

                  <el-empty v-else description="暂无评论，来抢沙发吧" />
                </template>
              </el-skeleton>

              <div v-if="commentTotal > commentSize" class="comment-pagination">
                <el-pagination
                  v-model:current-page="commentPage"
                  :page-size="commentSize"
                  :total="commentTotal"
                  layout="prev, pager, next"
                  background
                  @current-change="changeCommentPage"
                />
              </div>
            </section>
          </template>
        </template>
      </el-skeleton>
    </section>
  </main>
</template>

<style scoped>
.detail-page {
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
  width: min(1200px, calc(100% - 48px));
  margin: 0 auto;
}

.header-content {
  height: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #1677ff;
  font-size: 22px;
  font-weight: 700;
  cursor: pointer;
}

.logo-icon {
  width: 28px;
  height: 28px;
  display: inline-flex;
  justify-content: center;
  align-items: center;
  border-radius: 50%;
  background: #1677ff;
  color: #fff;
  font-size: 14px;
}

.container {
  padding-top: 30px;
  padding-bottom: 50px;
}

.video-player-box {
  position: relative;
  overflow: hidden;
  border-radius: 12px;
  background: #000;
  box-shadow: 0 8px 26px rgb(0 0 0 / 14%);
}

.video-player {
  width: 100%;
  max-height: 680px;
  display: block;
  background: #000;
}

.video-info {
  margin-top: 24px;
  padding: 26px 30px;
  border-radius: 12px;
  background: #fff;
}

.category {
  display: inline-block;
  padding: 4px 10px;
  border-radius: 5px;
  background: #e8f3ff;
  color: #1677ff;
  font-size: 13px;
}

h1 {
  margin: 14px 0 10px;
  font-size: 26px;
}

.statistics {
  display: flex;
  flex-wrap: wrap;
  gap: 15px;
  color: #9499a0;
  font-size: 14px;
}

.quality-switcher {
  position: absolute;
  z-index: 1;
  top: 14px;
  right: 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 6px;
  background: rgb(0 0 0 / 62%);
  color: #fff;
  font-size: 13px;
}

.interaction-actions {
  display: flex;
  gap: 10px;
  margin-top: 18px;
}

.author {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 24px;
}

.avatar {
  width: 44px;
  height: 44px;
  display: flex;
  justify-content: center;
  align-items: center;
  border-radius: 50%;
  background: #1677ff;
  color: #fff;
  font-size: 19px;
}

.author strong {
  display: block;
}

.author p {
  margin: 4px 0 0;
  color: #9499a0;
  font-size: 13px;
}

.author .el-button {
  margin-left: auto;
}

.description h3 {
  margin: 0 0 10px;
  font-size: 17px;
}

.description p {
  margin: 0;
  color: #61666d;
  line-height: 1.8;
  white-space: pre-wrap;
}

.comment-section {
  margin-top: 24px;
  padding: 26px 30px;
  border-radius: 12px;
  background: #fff;
}

.comment-header {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.comment-header h2 {
  margin: 0;
  font-size: 20px;
}

.comment-header span,
.comment-meta {
  color: #9499a0;
  font-size: 13px;
}

.comment-editor {
  display: flex;
  gap: 12px;
  align-items: flex-end;
  margin: 18px 0 24px;
}

.comment-editor .el-button {
  flex-shrink: 0;
}

.comment-list {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.comment-item {
  display: flex;
  gap: 12px;
}

.comment-avatar {
  flex: 0 0 auto;
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #e8f3ff;
  color: #1677ff;
}

.comment-main {
  min-width: 0;
  flex: 1;
}

.comment-name {
  color: #61666d;
  font-size: 14px;
}

.comment-main p {
  margin: 7px 0;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.comment-meta {
  display: flex;
  align-items: center;
  gap: 10px;
}

.comment-pagination {
  display: flex;
  justify-content: center;
  margin-top: 24px;
}

.reply-area {
  margin-top: 12px;
  padding: 12px;
  border-radius: 8px;
  background: #f7f8fa;
}

.reply-item {
  padding: 7px 0;
  color: #45474b;
  font-size: 14px;
  line-height: 1.6;
}

.reply-editor {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

@media (max-width: 700px) {
  .header-content,
  .container {
    width: min(100% - 28px, 1200px);
  }

  .video-info {
    padding: 20px;
  }

  .comment-section {
    padding: 20px;
  }

  .comment-editor {
    flex-direction: column;
    align-items: stretch;
  }

  h1 {
    font-size: 21px;
  }
}
</style>
