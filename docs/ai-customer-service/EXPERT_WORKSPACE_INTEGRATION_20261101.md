# 专家工作台整合记录

日期：2026-11-01。范围：`main/manager-web-next` 专家工作台与现有人工服务、媒体和标注能力的整合。本记录描述源码和验收边界；原始业务数据不得删除、清空或重新初始化。

## 来源与契约

| 能力 | aimeet 参考 | 小智 Server 对应实现与边界 |
| --- | --- | --- |
| 专家接待 | `apps/web/components/ExpertDashboard.tsx` 组织来电、转接、历史和专家状态；`services/api/app/business.py` 管理咨询业务 | `SupportWorkspace.vue` 使用人工请求、队列和统一 Conversation；接单和访问权限由 Server 判定，界面不得自行推断授权。 |
| 真人协作 | `apps/web/components/CallRoom.tsx`、`MediaCollaboration` 和 `services/api/app/integrations/livekit.py` | `MediaSessionOverlay.vue` 与 `mediaSession.mjs` 管理邀请、授权、连接和转接。服务状态、浏览器连接状态和媒体画面状态需分别展示；用户同意及目标准备完成前不应表示转接已完成。 |
| 截图与标注 | `apps/web/components/AnnotationEditor.tsx` 使用 Excalidraw 场景，绑定 consultation 和 frame | `AnnotationEditor.vue`、`annotationScene.mjs` 使用 Server 的 `annotation-v1` 场景及 frame。两种格式不宣称互相兼容；原始帧、标注和回复的权限及生命周期由 Server 契约约束。 |
| 对话与附件 | aimeet 的聊天及标注以其 consultation 为上下文 | 小智以同一 `conversation_id` 贯穿 AI、人工和媒体阶段；人工文字及附件继续进入统一消息账本。保存标注、加入回复草稿和实际发送是不同动作。 |

完整设计与既有约束见 [整合计划](AIMEET_INTEGRATION_PLAN.md)、[B4 契约](B4_IMPLEMENTATION_CONTRACT.md)、[B5 契约](B5_IMPLEMENTATION_CONTRACT.md)。以上是来源和目标契约比较，不意味着所有外部业务系统已接入。

## 本轮验证与待验收

- [x] 前端完整检查：38 个文件、475 项单元及组件测试，10 项契约测试，13 项快照测试；六种语言各 1924 个键的国际化检查和生产构建通过。构建提示 router 动态导入未形成独立分块，属构建优化提示。国际化脚本检查键一致性和重复键，不验证翻译质量或动态调用。
- [x] 浏览器技术验证：真实 API、双浏览器双向 WebRTC、授权截图、标注发送、全屏、画中画、输入保护、挂断清理及桌面和移动视口检查，范围及合成媒体来源见下文。
- [x] 最终移动视口检查：客户抽屉、标注历史及所选请求深链接恢复，标注画布适配与横向溢出检查通过；实际触摸验证范围见下文。
- [ ] 真实 App、物理设备和业务验收：身份接入、消息及附件、物理音视频、前后台、目标网络与真实转接等需逐项验证。当前 App 仍使用 Laravel / 既有 aimeet 链路，不能据此次工作认定 Android 已切换至 Server。
- [ ] 尚未覆盖的完整媒体和标注场景，包括拒绝、断线重连、转接成功和失败回退、旧身份撤销，以及消息图片、视频截图、失败重试和跨会话隔离，需按相应契约独立验收。

本轮未删除、清空或重新初始化现有数据库。部署及现场验收另行记录。

## aimeet 参考截图清单

以下本机截图位于 `/Users/lengwang/code/aimeet/.local/evidence/`，属于被 Git 忽略的参考材料；截图本身不能证明本轮小智实现或真实媒体验收通过。

| 参考视图 | 文件（相对于上述目录） | 对照重点 |
| --- | --- | --- |
| 专家通话桌面 | `expert-chat/final/live-expert-desktop.png` | 客户列表、主画面、聊天和协作工具布局。 |
| 专家通话移动端 | `expert-chat/final/live-expert-390x844.png` | 窄屏的可视区域和主要操作。 |
| 桌面截图标注 | `phase45/lan/annotation-editor-desktop.png` | 冻结帧、画布、工具和保存入口。 |
| 移动端截图标注 | `expert-chat/final/annotation-mobile-360x640.png` | 窄屏画布和工具的可操作性。 |
| 画中画拖动及全屏 | `expert-chat/final/pip-drag-mobile.png`、`expert-chat/final/fullscreen-mobile.png` | 本地画中画位置与全屏展示。 |
| 专家主画面切换 | `expert-chat/final/expert-main-mobile.png` | 主副画面切换后的布局。 |

对应源码为 `apps/web/components/ExpertDashboard.tsx`、`CallRoom.tsx`、`MediaCollaboration.tsx`、`AnnotationEditor.tsx` 及 `hooks/useKeyboardShortcuts.ts`。采集本轮小智对照截图时需记录页面、时间、视口及数据来源；截图不替代输入法保护、挂断确认或真实双端通话等交互验证。

## 本轮技术验证证据

证据目录：`/Users/lengwang/code/esp32/work/expert-workspace-20261101`。以下浏览器验证使用合成媒体和测试会话，不代表物理设备或真实业务验收。

- 变更前 `dev-before.json` 记录 SQL 备份 521136 字节，SHA-256 `6ad63e4bfb85b352bd0a18c6bed45f94404bbaeca7489517bce59b67a498d783`；基线计数为 4 个用户、25 条旧记录、33 条消息、120 条 changeset。
- 使用现有 `PUT support/agents` 接口为管理员 `2115948032647184386` 设置启用的专家档案、容量 3；密码未变更，未执行 SQL 迁移。
- 两个实际浏览器端分别使用 `taskSpace40` 管理员和 `qa-customer`，通过隔离的 `127.0.0.1` / `localhost` 主机访问同一 API。新建会话 `fff1c2119b444b61859626b1e320a978`，请求 `72ab2cf8b8164b34a01b634a19b7af61` 指定上述管理员。
- 实际双向 WebRTC 使用显式的振荡器音频和 Canvas 视频源，未使用物理麦克风或摄像头；每端各发生两次 `getUserMedia`（音频和视频），编辑、画面交换和全屏操作期间没有新增采集请求。
- 实时截图帧 `2116809939554856961`、标注 `2116810637524791298`、成图 `2116810637524791299` 包含手绘、箭头、圆圈三个图形；消息 `18fdb5c0092042eca5f6b6f4913e0add`（seq 1）发送文字及图片。`customer-evidence-proof.json` 记录三个私有资源 HTTP 200 和哈希；只读 SQL 核对确认三个附件关联同一消息和序号。
- 实际验证全屏进入和退出、画中画拖动及双击交换、输入框快捷键保护、原生挂断确认取消后通话继续，以及确认后媒体轨道、PeerConnection 和媒体 DOM 均清空；草稿得以保留。截图适配裁切和移动控件初始位置问题已发现并修复。
- 媒体会话 `2116809599967227905` 已结束，清理完成，活动槽位为 NULL、generation 为 1；人工请求 `72ab2cf8b8164b34a01b634a19b7af61` 仍处于活动状态。P2 测试 hook 已全部恢复并重载，轨道均结束、连接均关闭；P1 重载后无测试 hook。
- 首轮 `dev-after.json` 记录 SQL 备份 535768 字节，SHA-256 `f99bbbdbfa9843251ec9a9ef78d2b92dc2dd2afc4c061cf344f5feff4dfe820e`。原有 4 个用户、25 条旧记录和 33 条消息的行内容保持不变；此时消息总数 34（新增 1 条），changeset 仍为 120。
- 完整测试日志为 `frontend-final-tests.log`、`frontend-final-snapshots.log`、`frontend-final-i18n.log` 和 `frontend-final-build.log`，均位于上述证据目录；定向测试子集不重复计入完整测试总数。
- `mobile-final-proof.json` 记录 390 × 844 视口无横向溢出、无 Vite 错误覆盖层；侧栏完全在屏幕外（左边界 -224），客户和工具入口 y 坐标为 125。标注历史元数据和选中请求深链接可恢复，“查看”操作关闭抽屉；画布 234 × 131.625，容器区域 334 × 330.765。桌面 1440 × 1000 视口中画布 576.72 × 324.39，容器区域 763 × 346.91。
- 对照截图为 `desktop-final.png` 和 `mobile-editor-final.png`；已与原始 aimeet 参考图一起人工查看。移动端“查看”通过 DOM 点击触发，客户列表有一次实际 CDP 触摸验证；完整 Android 实体触摸验收尚未完成。
- 全屏、画中画及双浏览器 RTP 在上述真实浏览器技术验证中通过；后续修改涉及样式、模板和历史，未再次进行长通话。
- 清理技术采集 hook 后重新完整加载页面并打开持久标注，`final-browser-health.json` 确认 admin 专家档案启用、页面与画布正常、无 Vite 覆盖层、无残留合成采集 hook，新的 Runtime/Log 错误为零。清理前历史日志缓冲中曾有一条未保留 URL 的 HTTP 409，未归因；新加载及打开标注未复现。
- Vue 3 `http://127.0.0.1:18894/` 实际请求为 HTTP 200；API `http://127.0.0.1:18893/xiaozhi/user/pub-config` 返回业务 code 0、版本 0.9.8。早期检查误用了 `/pub/config`，其 HTTP 200 中包含业务 401，不能作为公共配置成功依据。沿用原 Dev 后端、数据库和正在运行的 Vue 开发服务；本轮未修改 Java/Python 服务或数据库迁移，未重复运行其既有测试来增加计数。

### 已发送标注的再次回复

最终复核发现，附件只允许绑定一条消息，历史中的已发送成图不能直接加入新消息。已将此操作改为「另存新快照」，进入既有编辑器复制流程；入口和草稿加入均按实际消息中的附件 ID 防止重复绑定，未放宽后端权限或绑定限制。

`sent-copy-http-proof.json` 记录真实 HTTP 验证：复用旧成图返回预期 409；复制生成标注 `2116817483438419970` 和新成图 `2116817483442614273`，新消息 `d6cb4be3bcad46ff87279ac3def65795`（seq 2）发送成功，相同客户端编号重试返回同一消息，原消息保持不变。`sent-copy-customer-proof.json` 确认客户身份可读副本并看到新消息。该补充使用现有账号认证和真实接口，无新浏览器采集；上述界面截图早于最后的历史按钮文案及重复绑定保护补丁，主体布局未变。

补充验证后的 `dev-final.sql` 为 539719 字节，SHA-256 `a853f09c0da7990eb02f18efe6be2f22416abe342a81445633a8a465c1c541a7`；`dev-final.json` 逐行核对初始备份，原 4 个用户、25 条旧记录和 33 条消息均未改变或移除，最终消息 35 条（仅新增两条验收回复）。备份和测试证据均保留在上述本机目录。


## 参考界面对照

| 对照项 | 本轮实现和保留边界 |
| --- | --- |
| 四个区域 | 组织客户列表、中央媒体画面、文字沟通和协作工具。 |
| 绿色与导航 | 沿用参考的绿色视觉线索，同时保留现有管理后台导航。 |
| 内嵌标注画布 | 冻结画面中进行标注；保留独立的保存、加入草稿及发送动作。 |
| 主画面与画中画 | 提供本地预览、主次画面交换及媒体控制；实际浏览器技术验证如上。 |
| 手机抽屉 | 客户列表和协作工具使用移动端抽屉，最终视口检查结果如上。 |

计费及邀请运营功能未迁入本轮核心范围。
