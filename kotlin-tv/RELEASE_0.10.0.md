# 0.10.0：轻量签名与 App 目录分类

平台侧封禁了旧的固定设备指纹与重签名链路，本版整体切换到轻量方案，并把首页/分类浏览迁移到 App 目录接口。参考并改编自 [jipinwa/hongguo](https://github.com/jipinwa/hongguo)（GPL-3.0-only）。

## 变更

- 签名：移除 drpys gorgon/helios/medusa 重签名（`Signer`/`AesV3` 及签名预言机测试），改为轻量 X-Gorgon/X-Khronos/X-SS-STUB 方案（`SignerLight`），带对照 Go 实现的固定向量测试。
- 设备身份：每次安装生成随机 19 位 device_id/iid，不再共用硬编码指纹；`version_code` 提升到 73532。
- 分类浏览：`home()` 从网页分类 HTML 切换到签名 `landpage/v` 目录；分页使用服务端游标（offset + session_id + has_more），页签名拦截重页，30 分钟过期会话自动重建；已取行本地累积，上一页直接回放缓存。
- 分类扩展：新增 AI 剧（`ai_series`），首页可选 短剧 / 漫剧 / AI剧；漫剧热播榜保留为独立榜单页。
- 搜索：旧 `search/tab/v` 接口对任何签名返回空 200，短剧/AI剧改用官网搜索；漫剧在类型目录内本地匹配（复用同一 feed 游标）。
- 播放：`multi_video_model → fallback_api` 链路停止返回数据，改用 `video_model/v1` 直取 `video_list`，支持 `encrypt_info`（base64 地址 + `spade_a` 密钥）条目。

## 本地验证

- JVM 测试 84 项通过（新增：游标推进、翻页回退、耗尽停止、重页拦截、AI 剧 genre、搜索 feed 扫描）。
- 真实接口冒烟：漫剧 20 条/页且第 2 页零重叠；AI剧 20 条/页；短剧新目录可用；详情→取流→CENC 解密全链路通过。
- Debug APK 构建成功（9.1M）。

## 待实机确认

Android 8 实体电视上三分类浏览、游标翻页与播放稳定性仍需实机验收。
