# 验证记录

日期：2026-11-04。

- 拆分后管理前端重新 npm ci、vue-tsc -b、vite build：通过，1658 modules，构建 5.53 秒。保留大于 500 kB 分块提示，未把它当作构建失败。
- 拆分后后端：40 项 pytest 通过，16.94 秒，1 条 Starlette/AnyIO 弃用提示。
- Android 源码与拆分前已验证版本一致：20 项 OCR 单测通过，Debug 与 Release 构建通过；Release 使用 example.invalid 仅作编译检查，未连接或部署该地址。
- Release 未传地址和传 HTTP 地址均按预期被构建门禁拦截；release 清单禁止明文，debug 开发允许明文。
- 未运行真实 PostgreSQL/Redis/容器 TLS 集成、生产数据迁移、真实手机 UI 与 OCR 准确率回归。
- 前端生成 JS 已从仓库排除，TypeScript 使用 noEmit，仅维护 TS/Vue 源文件。
- 不上传生产 .env、数据库、storage、TLS 私钥、APK、构建缓存或 node_modules。
