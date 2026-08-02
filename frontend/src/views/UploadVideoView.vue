<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useRouter } from 'vue-router'
import { createVideo, uploadCover, uploadVideo } from '../api/creator-video'
import { getCategories, type VideoCategory } from '../api/video'
import SiteHeader from '../components/SiteHeader.vue'

const router = useRouter()
const formRef = ref<FormInstance>()

const categories = ref<VideoCategory[]>([])
const categoryLoading = ref(false)
const coverUploading = ref(false)
const videoUploading = ref(false)
const submitting = ref(false)
const coverPreviewUrl = ref('')

const form = reactive({
  title: '',
  description: '',
  categoryId: undefined as number | undefined,
  // 后端自动截帧生成封面；仅保留该字段兼容尚未刷新的旧页面结构。
  coverObjectName: '',
  videoObjectName: '',
  duration: 0
})

const rules: FormRules = {
  title: [
    { required: true, message: '请输入视频标题', trigger: 'blur' },
    { max: 100, message: '标题不能超过 100 个字符', trigger: 'blur' }
  ],
  categoryId: [
    { required: true, message: '请选择投稿分区', trigger: 'change' }
  ],
  description: [
    { max: 2000, message: '简介不能超过 2000 个字符', trigger: 'blur' }
  ]
}

const durationText = computed(() => {
  if (!form.duration) {
    return '上传视频后自动识别时长'
  }

  const minutes = Math.floor(form.duration / 60)
  const seconds = form.duration % 60

  return `${minutes}:${String(seconds).padStart(2, '0')}`
})

function ensureLoggedIn() {
  if (localStorage.getItem('token')) {
    return true
  }

  ElMessage.warning('请先登录后再投稿')
  router.replace('/login')
  return false
}

async function loadCategories() {
  try {
    categoryLoading.value = true
    categories.value = await getCategories()
  } catch (error) {
    ElMessage.error(
      error instanceof Error ? error.message : '获取视频分区失败'
    )
  } finally {
    categoryLoading.value = false
  }
}

function validateImage(file: File) {
  if (!['image/jpeg', 'image/png'].includes(file.type)) {
    ElMessage.error('封面仅支持 JPG、PNG 格式')
    return false
  }

  if (file.size > 10 * 1024 * 1024) {
    ElMessage.error('封面图片不能超过 10MB')
    return false
  }

  return true
}

async function handleCoverChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]

  if (!file || !validateImage(file)) {
    return
  }

  if (coverPreviewUrl.value) {
    URL.revokeObjectURL(coverPreviewUrl.value)
  }

  coverPreviewUrl.value = URL.createObjectURL(file)

  try {
    coverUploading.value = true
    form.coverObjectName = await uploadCover(file)
    ElMessage.success('封面上传成功')
  } catch (error) {
    form.coverObjectName = ''
    ElMessage.error(
      error instanceof Error ? error.message : '封面上传失败'
    )
  } finally {
    coverUploading.value = false
    input.value = ''
  }
}

async function handleVideoChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]

  if (!file) {
    return
  }

  if (!file.type.startsWith('video/')) {
    ElMessage.error('请选择视频文件')
    return
  }

  if (file.size > 500 * 1024 * 1024) {
    ElMessage.error('视频文件不能超过 500MB')
    return
  }

  try {
    videoUploading.value = true

    const uploadResult = await uploadVideo(file)
    if (!uploadResult.detectedDuration) {
      throw new Error('后端未能探测视频时长')
    }

    form.videoObjectName = uploadResult.objectName
    form.duration = uploadResult.detectedDuration

    ElMessage.success('视频上传成功')
  } catch (error) {
    form.videoObjectName = ''
    form.duration = 0

    ElMessage.error(
      error instanceof Error ? error.message : '视频上传失败'
    )
  } finally {
    videoUploading.value = false
    input.value = ''
  }
}

async function submit() {
  if (!formRef.value || !ensureLoggedIn()) {
    return
  }

  const valid = await formRef.value.validate().catch(() => false)

  if (!valid) {
    return
  }

  if (!form.coverObjectName) {
    ElMessage.warning('请先上传视频封面')
    return
  }

  if (!form.videoObjectName || !form.duration) {
    ElMessage.warning('请先上传视频文件')
    return
  }

  try {
    submitting.value = true

    await createVideo({
      categoryId: form.categoryId!,
      title: form.title.trim(),
      description: form.description.trim(),
      coverObjectName: form.coverObjectName,
      videoObjectName: form.videoObjectName,
      duration: form.duration
    })

    ElMessage.success('投稿已提交，等待管理员审核')
    router.push('/profile')
  } catch (error) {
    ElMessage.error(
      error instanceof Error ? error.message : '投稿提交失败'
    )
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  if (ensureLoggedIn()) {
    loadCategories()
  }
})

onBeforeUnmount(() => {
  if (coverPreviewUrl.value) {
    URL.revokeObjectURL(coverPreviewUrl.value)
  }
})
</script>

<template>
  <main class="upload-page">
    <SiteHeader max-width="1320px">
      <template #nav>
        <span class="creator-label">创作中心</span>
      </template>
      <template #actions>
        <el-button text @click="router.push('/profile')">稿件管理</el-button>
        <el-button @click="router.push('/')">返回主站</el-button>
      </template>
    </SiteHeader>

    <section class="creator-shell">
      <aside class="creator-sidebar">
        <div class="sidebar-heading">
          <span>创作工作台</span>
          <strong>发布视频</strong>
        </div>
        <ol class="publish-steps">
          <li :class="{ complete: Boolean(form.videoObjectName) }">
            <span>1</span>
            <div><strong>上传视频</strong><small>{{ form.videoObjectName ? '已完成' : '等待上传' }}</small></div>
          </li>
          <li :class="{ complete: Boolean(form.title && form.categoryId) }">
            <span>2</span>
            <div><strong>完善信息</strong><small>标题、分区与简介</small></div>
          </li>
          <li :class="{ complete: Boolean(form.coverObjectName) }">
            <span>3</span>
            <div><strong>设置封面</strong><small>{{ form.coverObjectName ? '已完成' : '16:9 更吸引观众' }}</small></div>
          </li>
        </ol>
        <div class="publish-guide">
          <strong>投稿小贴士</strong>
          <p>清晰的标题和封面更容易获得点击。请确认内容符合社区规范并拥有发布权利。</p>
        </div>
      </aside>

      <section class="upload-workbench">
        <div class="heading">
          <span class="heading-kicker">VIDEO SUBMISSION</span>
          <h1>发布新视频</h1>
          <p>上传完成后将自动转码并进入审核，审核通过后会展示在主站。</p>
        </div>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
        >
          <section class="form-section video-file-section">
            <div class="form-section__title">
              <div>
                <span class="step-number">01</span>
                <h2>上传视频文件</h2>
              </div>
              <small>支持 MP4，最大 500MB</small>
            </div>
            <label class="video-dropzone" :class="{ uploaded: form.videoObjectName }">
              <input
                type="file"
                accept="video/*"
                :disabled="videoUploading"
                @change="handleVideoChange"
              />
              <span class="dropzone-icon">{{ form.videoObjectName ? '✓' : '↑' }}</span>
              <strong>{{ form.videoObjectName ? '视频上传完成' : '点击选择视频文件' }}</strong>
              <p>{{ form.videoObjectName ? `已识别时长 ${durationText}` : '选择后将立即上传，请保持页面开启' }}</p>
              <span class="dropzone-action">
                {{ form.videoObjectName ? '重新选择视频' : '选择文件' }}
              </span>
              <div v-if="videoUploading" class="mask">
                <strong>正在上传视频</strong>
                <span>请勿关闭或离开当前页面…</span>
              </div>
            </label>
          </section>

          <div class="metadata-layout">
            <div class="metadata-main">
              <section class="form-section">
                <div class="form-section__title">
                  <div>
                    <span class="step-number">02</span>
                    <h2>完善稿件信息</h2>
                  </div>
                </div>

                <el-form-item label="视频标题" prop="title">
                  <el-input
                    v-model="form.title"
                    size="large"
                    maxlength="100"
                    show-word-limit
                    placeholder="用清晰、有吸引力的标题介绍你的视频"
                  />
                </el-form-item>

                <el-form-item label="投稿分区" prop="categoryId">
                  <el-select
                    v-model="form.categoryId"
                    :loading="categoryLoading"
                    size="large"
                    placeholder="请选择最符合内容的分区"
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
                    v-model="form.description"
                    type="textarea"
                    :rows="7"
                    maxlength="2000"
                    show-word-limit
                    placeholder="介绍视频亮点、创作背景或相关信息（可选）"
                  />
                </el-form-item>
              </section>
            </div>

            <aside class="cover-panel">
              <div class="form-section__title">
                <div>
                  <span class="step-number">03</span>
                  <h2>视频封面</h2>
                </div>
                <span class="required-badge">必填</span>
              </div>
              <div class="cover-upload">
                <img
                  v-if="coverPreviewUrl"
                  :src="coverPreviewUrl"
                  alt="封面预览"
                />
                <div v-else class="cover-placeholder">
                  <span>▧</span>
                  <strong>16:9 封面</strong>
                  <small>JPG / PNG / WebP，最大 10MB</small>
                </div>
                <div v-if="coverUploading" class="mask">正在上传封面…</div>
              </div>
              <label class="select-file">
                <input
                  type="file"
                  accept="image/*"
                  :disabled="coverUploading"
                  @change="handleCoverChange"
                />
                {{ form.coverObjectName ? '重新选择封面' : '选择封面图片' }}
              </label>
              <p class="cover-tip">封面应与视频内容相关，避免过多文字和低清晰度图片。</p>
            </aside>
          </div>

          <div class="actions">
            <div>
              <strong>提交前请确认</strong>
              <span>视频、标题、分区和封面均已准备完成</span>
            </div>
            <el-button size="large" @click="router.push('/')">取消</el-button>
            <el-button
              type="primary"
              size="large"
              :loading="submitting"
              :disabled="coverUploading || videoUploading"
              @click="submit"
            >
              提交投稿
            </el-button>
          </div>
        </el-form>
      </section>
    </section>
  </main>
</template>

<style scoped>
.upload-page {
  min-height: 100vh;
  background: #f6f7f8;
  color: #18191c;
}

.header {
  height: 64px;
  background: #fff;
  border-bottom: 1px solid #e7e7e7;
}

.header-content {
  width: min(960px, calc(100% - 32px));
  height: 100%;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.logo {
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

.upload-card {
  width: min(760px, calc(100% - 32px));
  margin: 32px auto 48px;
  padding: 32px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 3px 12px rgb(0 0 0 / 4%);
}

.heading {
  margin-bottom: 28px;
}

.heading h1 {
  margin: 0 0 8px;
  font-size: 26px;
}

.heading p {
  margin: 0;
  color: #7a7f87;
  font-size: 14px;
}

.full-width {
  width: 100%;
}

.file-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
  margin: 8px 0 30px;
}

/* 封面由后端 FFmpeg 自动截帧，投稿页不再要求用户手动上传。 */
.file-field:first-child {
  display: block;
}

.file-field > label:first-child {
  display: block;
  margin-bottom: 10px;
  font-size: 14px;
  font-weight: 600;
}

.required {
  color: #f56c6c;
}

.cover-upload,
.video-upload {
  position: relative;
  overflow: hidden;
  display: grid;
  aspect-ratio: 16 / 9;
  place-items: center;
  border: 1px dashed #cdd0d6;
  border-radius: 8px;
  background: #f7f8fa;
  color: #909399;
}

.cover-upload img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.video-upload {
  align-content: center;
  gap: 8px;
}

.video-upload strong {
  color: #4e5969;
}

.mask {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  background: rgb(0 0 0 / 55%);
  color: #fff;
  font-size: 14px;
}

.select-file {
  display: inline-block;
  margin-top: 10px;
  color: #1677ff;
  font-size: 14px;
  cursor: pointer;
}

.select-file input {
  display: none;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

@media (max-width: 600px) {
  .upload-card {
    margin-top: 16px;
    padding: 22px;
  }

  .file-grid {
    grid-template-columns: 1fr;
    gap: 18px;
  }

  .actions .el-button {
    flex: 1;
  }
}
</style>

<style scoped>
.upload-page {
  min-height: 100vh;
  background: var(--vn-page);
}

.creator-label {
  padding-left: 16px;
  border-left: 1px solid var(--vn-border);
  color: var(--vn-text-secondary);
  font-size: 14px;
  font-weight: 600;
}

.creator-shell {
  width: min(1320px, calc(100% - 48px));
  margin: 0 auto;
  padding: 28px 0 70px;
  display: grid;
  grid-template-columns: 230px minmax(0, 1fr);
  align-items: start;
  gap: 22px;
}

.creator-sidebar {
  position: sticky;
  top: 92px;
  padding: 22px 18px;
  border: 1px solid var(--vn-border-light);
  border-radius: 14px;
  background: #fff;
}

.sidebar-heading span,
.sidebar-heading strong {
  display: block;
}

.sidebar-heading span {
  color: var(--vn-text-muted);
  font-size: 11px;
  letter-spacing: 1.3px;
}

.sidebar-heading strong {
  margin-top: 5px;
  font-size: 20px;
}

.publish-steps {
  margin: 24px 0;
  padding: 0;
  list-style: none;
}

.publish-steps li {
  position: relative;
  display: flex;
  gap: 11px;
  padding: 0 0 24px;
  color: var(--vn-text-muted);
}

.publish-steps li:not(:last-child)::after {
  content: "";
  position: absolute;
  top: 30px;
  left: 14px;
  width: 1px;
  height: 18px;
  background: var(--vn-border);
}

.publish-steps li > span {
  width: 29px;
  height: 29px;
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #f1f2f3;
  font-size: 12px;
  font-weight: 700;
}

.publish-steps li.complete > span {
  background: var(--vn-primary);
  color: #fff;
}

.publish-steps strong,
.publish-steps small {
  display: block;
}

.publish-steps strong {
  color: var(--vn-text-secondary);
  font-size: 13px;
}

.publish-steps small {
  margin-top: 2px;
  font-size: 11px;
}

.publish-guide {
  padding: 14px;
  border-radius: 10px;
  background: linear-gradient(135deg, var(--vn-primary-soft), #fff);
}

.publish-guide strong {
  color: var(--vn-primary-dark);
  font-size: 12px;
}

.publish-guide p {
  margin: 7px 0 0;
  color: var(--vn-text-muted);
  font-size: 11px;
  line-height: 1.7;
}

.upload-workbench {
  min-width: 0;
}

.heading {
  margin-bottom: 22px;
}

.heading-kicker {
  color: var(--vn-primary);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 1.5px;
}

.heading h1 {
  margin: 5px 0 6px;
  font-size: 28px;
  letter-spacing: -.7px;
}

.heading p {
  margin: 0;
  color: var(--vn-text-muted);
  font-size: 13px;
}

.form-section,
.cover-panel {
  padding: 24px;
  border: 1px solid var(--vn-border-light);
  border-radius: 14px;
  background: #fff;
}

.video-file-section {
  margin-bottom: 18px;
}

.form-section__title {
  margin-bottom: 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.form-section__title > div {
  display: flex;
  align-items: center;
  gap: 9px;
}

.form-section__title h2 {
  margin: 0;
  font-size: 17px;
}

.form-section__title > small {
  color: var(--vn-text-muted);
  font-size: 11px;
}

.step-number {
  color: var(--vn-primary);
  font-size: 11px;
  font-weight: 800;
}

.video-dropzone {
  position: relative;
  min-height: 230px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border: 1px dashed #a9dff2;
  border-radius: 12px;
  background:
    radial-gradient(circle at 50% 0%, rgb(94 216 255 / 17%), transparent 42%),
    #f8fcfe;
  cursor: pointer;
  transition: .2s;
}

.video-dropzone:hover {
  border-color: var(--vn-primary);
  background-color: var(--vn-primary-soft);
}

.video-dropzone.uploaded {
  border-style: solid;
  border-color: #a8dfb8;
  background: #f5fff8;
}

.video-dropzone input,
.select-file input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
}

.dropzone-icon {
  width: 52px;
  height: 52px;
  margin-bottom: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 8px 24px rgb(0 174 236 / 16%);
  color: var(--vn-primary);
  font-size: 24px;
  font-weight: 700;
}

.video-dropzone > strong {
  font-size: 16px;
}

.video-dropzone > p {
  margin: 6px 0 14px;
  color: var(--vn-text-muted);
  font-size: 12px;
}

.dropzone-action {
  padding: 7px 16px;
  border-radius: 7px;
  background: var(--vn-primary);
  color: #fff;
  font-size: 12px;
}

.mask {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 5px;
  background: rgb(255 255 255 / 92%);
  color: var(--vn-primary-dark);
  backdrop-filter: blur(8px);
}

.mask span {
  color: var(--vn-text-muted);
  font-size: 11px;
}

.metadata-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 310px;
  align-items: start;
  gap: 18px;
}

.form-section :deep(.el-form-item__label) {
  color: var(--vn-text-secondary);
  font-weight: 600;
}

.form-section :deep(.el-input__wrapper),
.form-section :deep(.el-textarea__inner),
.form-section :deep(.el-select__wrapper) {
  background: #f8f9fa;
}

.cover-panel {
  padding: 20px;
}

.required-badge {
  padding: 3px 7px;
  border-radius: 5px;
  background: #fff0f4;
  color: var(--vn-accent);
  font-size: 10px;
}

.cover-upload {
  position: relative;
  overflow: hidden;
  aspect-ratio: 16 / 9;
  border: 1px dashed var(--vn-border);
  border-radius: 10px;
  background: #f6f7f8;
}

.cover-upload img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
}

.cover-placeholder {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--vn-text-muted);
}

.cover-placeholder > span {
  margin-bottom: 7px;
  font-size: 30px;
}

.cover-placeholder strong,
.cover-placeholder small {
  display: block;
}

.cover-placeholder strong {
  color: var(--vn-text-secondary);
  font-size: 13px;
}

.cover-placeholder small {
  margin-top: 3px;
  font-size: 10px;
}

.select-file {
  position: relative;
  width: 100%;
  margin-top: 12px;
  padding: 9px 12px;
  display: block;
  border: 1px solid var(--vn-primary);
  border-radius: 8px;
  color: var(--vn-primary-dark);
  text-align: center;
  font-size: 12px;
  cursor: pointer;
}

.cover-tip {
  margin: 11px 0 0;
  color: var(--vn-text-muted);
  font-size: 10px;
  line-height: 1.6;
}

.actions {
  position: sticky;
  z-index: 8;
  bottom: 12px;
  margin-top: 18px;
  padding: 14px 18px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  border: 1px solid var(--vn-border-light);
  border-radius: 12px;
  background: rgb(255 255 255 / 94%);
  box-shadow: 0 10px 30px rgb(0 0 0 / 8%);
  backdrop-filter: blur(16px);
}

.actions > div {
  margin-right: auto;
}

.actions strong,
.actions span {
  display: block;
}

.actions strong {
  font-size: 12px;
}

.actions span {
  margin-top: 2px;
  color: var(--vn-text-muted);
  font-size: 10px;
}

@media (max-width: 980px) {
  .creator-shell {
    grid-template-columns: 1fr;
  }

  .creator-sidebar {
    position: static;
    display: none;
  }
}

@media (max-width: 760px) {
  .creator-shell {
    width: min(100% - 24px, 1320px);
    padding-top: 18px;
  }

  .metadata-layout {
    grid-template-columns: 1fr;
  }

  .video-dropzone {
    min-height: 200px;
  }

  .actions {
    bottom: 6px;
    flex-wrap: wrap;
  }

  .actions > div {
    width: 100%;
  }

  .actions .el-button {
    flex: 1;
  }
}
</style>
