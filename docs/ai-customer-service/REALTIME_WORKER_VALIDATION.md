# P3 realtime worker验证

2026-10-30。真实Java/Python桥接R6通过23项；当前loopback HTTP/WebSocket网络回归31项通过。ACK修复的Java定向30项通过，其中21项使用全新 `xiaozhi_realtime_test_allocated_ack_20261030_r1`，JDBC/MySQL会话匹配UTC；原行保留，无重置。父代理负责合并后最终全量计数。

真实R6使用全新 `xiaozhi_ai_test_rt_http_20261030_205525_8075a1`、独立Spring API `127.0.0.1:19899`、Python worker `127.0.0.1:18900`、实际百炼模型。JDBC与SQL会话均为+08:00。Redis使用当时确认空的独立逻辑DB10并预设版本键，避开旧启动代码的FLUSHDB；没有清空已有Redis数据。使用仓库技术WAV转PCM，未使用浏览器或麦克风；服务进程均已停止，测试schema和证据保留。

23项覆盖：真实provider ready、owner隔离/票据单用、原始PCM和真实回复音频、实际转写/assistant持久化及user-before-assistant顺序、Java接受committed后再response、持久文字真实item ACK及回复、实际interrupt/command ACK/保留canceled partial、结束后B2重新入队、新session携历史context真实ACK、人工handoff关闭、旧lease的真实callback重放409与无迟到event行。handoff后0.265秒关闭。R6收到音频合计290400字节；两次完整响应的实际usage分别497/74/571与536/24/560（输入/输出/合计）。取消/接管轮缺少完整usage时保持空，不估算。

实际提供方为文本新分配item_* ID，不回显请求item.id。ACK修复同时传request_item_id验证Java派发身份、保存真实provider_item_id；worker必须单pending、精确role/content_type/text echo、真实previous_item_id锚全部匹配，不能仅靠顺序。31项网络回归包含真实分配ID形状、错误内容/role/锚、context不匹配、重复/迟到ACK、音频append阶段早到input item、取消竞态、撤权、队列/资源释放；Java覆盖伪造request ID、旧lease、缺字段、重复ACK及provider ID复用。

证据绝对路径：

- `/Users/lengwang/code/esp32/work/realtime-bridge-proof-r6.json`
- `/Users/lengwang/code/esp32/work/realtime-bridge-e2e-r6.log`
- `/Users/lengwang/code/esp32/work/realtime-worker-allocated-ack-r2.log`
- `/Users/lengwang/code/esp32/work/realtime-allocated-ack-java-r1.log`

R2/R4保留了原ID相等规则导致的真实注入超时证据；R3遇磁盘满而迁移失败，失败schema保留。没有把这些失败记录改为成功。只回收过本任务已停止runtime里的两份重复JAR，源构建及日志/proof保留；后续使用硬链接避免重复占盘。

复现入口为 `main/xiaozhi-server/tests/realtime_bridge_e2e.py`，必须显式 `--allow-real-provider`，所有私密路径和Java/worker解释器由参数提供，测试数据库密码通过 `REALTIME_E2E_DB_PASSWORD` 环境提供。脚本不含真实key或机器私密默认路径；只创建新schema与自有localhost进程，透明诊断只记事件类型/ID/文本哈希，不记原文、音频或key。

交付仍是manual技术MVP，非自动VAD/WebRTC。真实摄像头输入、浏览器/安卓麦克风权限与播放回声、HTTPS/WSS部署、弱网/现场机械声及业务工具另行验收。实时camera仅在网络mock验证实际JPEG转发；本轮真实provider E2E没有摄像头。技术录音或普通TTS不构成机械故障诊断验收。

## 主工作树最终补验

ACK五个源码文件与验证工作树逐一SHA一致，主工作树Java全量457、Vue368+10契约、实时网络31通过。恢复副本当时运行最终整合JAR、独立worker18902，现备用进程已正常停止而数据/配置/manifest保留，原Dev及其worker继续运行，详见恢复记录。真实采样图问答补验见 INTEGRATION_20261030.md：同轮语音提问可正确描述技术图标；后续纯文字对旧帧的追问没有可靠视觉结果，不宣传该能力。

交付的可重复E2E脚本在R6后将运行JAR由硬链接改为独立复制，先检查磁盘余量，以避免并行构建修改正在运行的文件；该小运行隔离调整通过语法检查，未重复调用provider。R6当时的JAR hash及实际成功证据保持原样。

## 原 Dev 恢复后的浏览器启动与结束边界

原Docker Dev18894/API18893和原MySQL/Redis、S3已恢复，见 [恢复记录](./DEV_RECOVERY_VALIDATION_20261030.md)。同一浏览器页面的无import最小Worklet probe已定位Ego工具auto-attach filter暂停worklet的问题；调整该工具filter后probe ready，未因此修改Vite/worklet导入。实际Vite dev响应是module，无importScripts，不能以classic worker错误解释本次挂起。

浏览器媒体非权限启动阶段的10秒超时/取消释放、HTTP明确结束前保留WebSocket、socket-close不冒充成功及身份异步fencing已修复，5个realtime定向套件47项和build通过。权限prompt仍不限时，原有授权重验保留。23:33真实浏览器39秒录音与技术画面采样问答通过，模型正确描述青绿色正方形和深蓝背景，转写及回复持久历史可见，17字文字草稿保留。随后会话正常ended，UI确认保存并关闭本机设备，8条技术track全ended，17字草稿保留；之后reload恢复原生输入（无qaHook/captureHook），正常UI发送恢复验证文字，实际B2答“文字恢复正常”。移动overlay右边378<390，无横向溢出。技术输入不是物理麦克风、摄像头或现场声音。
