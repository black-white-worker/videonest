<template>
  <RouterView />
</template>

<style>
/* 跨页面基础框架只提供底色和基础间距，具体布局由各页面负责。 */
.upload-page, .profile-page, .notification-page, .admin-page, .page {
  min-height: 100vh;
  background: var(--vn-page) !important;
  color: var(--vn-text);
}

.upload-page .header, .profile-page .header, .notification-page .header,
.admin-page .header, .page > header {
  background: rgb(255 255 255 / 94%) !important;
  border-color: var(--vn-border) !important;
  backdrop-filter: blur(14px);
}

.upload-page .container, .profile-page .container, .notification-page .container,
.admin-page .container, .page .container { padding-bottom: 56px; }

.el-dialog { border-radius: var(--vn-radius) !important; }
.el-dialog__header { margin-right: 0 !important; padding: 22px 24px 16px !important; border-bottom: 1px solid var(--vn-border); }
.el-dialog__body { padding: 24px !important; }
@media (max-width:700px) { .upload-page .container, .profile-page .container, .notification-page .container, .admin-page .container, .page .container { padding-bottom: 32px; } }
</style>
