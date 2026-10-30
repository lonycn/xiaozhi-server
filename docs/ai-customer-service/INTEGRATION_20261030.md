# 2026-10-30 整合交付记录

Dev 分支整合了非实时 Omni 原始音轨、P3 实时 AI、B5 真人音视频及换房转接、B6 默认关闭的来源身份、内部交接摘要和 Vue3。原始18节规划文件完整保留。下述技术样本、运行副本和现场验收分别记录；原Dev恢复后的最新状态以 [恢复与补充验收](./DEV_RECOVERY_VALIDATION_20261030.md) 为准。

## 最终检查

- Java 主工作树全量457项通过，零失败/错误/跳过，产出独立Spring Boot JAR。所有持久化测试使用新隔离库，已有及失败测试库保留；日志 `work/integrated-java-r2.log`。
- Vue3整合基线为10项契约、368项单元/组件；恢复及浏览器修复后最新逻辑全量为387项单元/组件、10项契约、13项snapshot辅助，6语言各1840 key一致，生产构建通过；之后仅移动CSS断点修复再次build通过。定向子集不与全量相加。
- 实时worker31项真实loopback HTTP/WS网络检查、普通AI worker39项、Dev管理37项通过。不同平台重复检查不相加。
- 启动版本变化不再FLUSHDB；4项缺失/空/旧/相同版本回归通过。模型delta的精确内部路由保留字面原文，先鉴权、请求体1MiB上限和严格字段校验，其余XSS策略保留。

## 真实提供方与媒体

- P3真实Java→Python→百炼R6共23项通过：票据单用、所有者隔离、音频、转写/回复账本及顺序、真实usage、真实分配item ID的文字ACK、打断/命令确认、结束恢复B2、携历史新会话、人工接管及旧lease409无迟到写入。实际完整usage497/74/571及536/24/560；音频290400字节，接管关闭0.265秒。取消/接管无完整usage时不估算。
- 文本ACK同时核对原request_item_id及真实provider_item_id；单pending、role/content type/精确text echo/previous_item_id锚均须匹配。不能用发出socket消息代替provider确认。见 REALTIME_AI_CONTRACT.md、REALTIME_WORKER_VALIDATION.md。
- 恢复副本真实PCM+两帧JPEG同轮问答返回音频810720字节、usage579/259/838，并正确描述仓库图标的白蓝机器人和青蓝眼睛。输入问题是明确标注的本机合成语音，无物理摄像头或麦克风；不构成现场验收。证明 `work/realtime-camera-proof-r3.json`。
- 较早R1验证传输和落账；R2虽传输检查通过，后续纯文字追问却回答看不到旧画面，因此不宣称历史帧追问可靠。六语言提示已说明在本段录音中问画面，录音结束停止采样、纯文字不发送新图。当前手动60秒分段+1fps JPEG，不冒称自动VAD/连续视频/WebRTC/AOQ效果。
- 非实时Omni：原Dev S3录音全链实际usage183/175/358、audio44。独立私有文件系统再完成录音183/118/301、audio44及有声视频865/217/1082、audio16。均为实际模型和持久回复，文件系统结果不称为S3结果；234秒左右inline音轨限制仍明确存在。见 OMNI_AUDIO_VALIDATION.md。
- B5实际LiveKit SDK双向音频、新房转接、旧客服cutoff、旧JWT仅旧空房、drain和视频解码22帧→B3/B5→挂断后B4标注发送三证据通过。SDK退出清理警告及失败脚本证据保留。见 B5_RUNTIME_VALIDATION.md。
- 内部九字段摘要为可编辑原文草稿，事实/用户陈述/AI推断和来源绑定、版本及离任cutoff有真实MySQL RR和Spring装配检查。实际恢复副本HTTP转人工生成9字段，owner读取内部摘要404。它不是模型语义总结，也不自动公开给用户。见 HANDOFF_SUMMARY_VALIDATION.md。

## 运行与数据保留

Docker VM在本轮出现真实存储I/O错误，影响多项目。原18894后端连接中断；故障初期没有全局重启、清库、删除卷或覆盖原数据；随后用户明确授权恢复，两个Docker profile的正常restart及数据保留结果见恢复记录。更新前完整备份为 `dev/.runtime/backups/before-integrated-media-20261030T123914Z.sql`，303525字节，SHA256 `8e71e06ee7bc986f3e5fac8e7f540d8ba5c30ee69ee60a39d5a8d4000bbd6eec`；含25条旧设备历史、1用户、11条统一消息、112条迁移。

备份只恢复到一个新的隔离native库，保留原卷。恢复副本当时的Vue3为127.0.0.1:18895、API18901、独立实时bridge18902；页面标“20:39备份”。该副本当时运行最终整合JAR，迁移增至116，旧历史25和用户1保持不变。备份后的原Dev技术测试行仍在原卷，没有用副本覆盖。这些备用进程现已正常SIGTERM停止，数据/DB/volumes及manifest/私有配置保留可恢复；副本不是原库当前最新状态。私密凭据和配置仅保存在本机权限受限目录，不入库。

故障期间S3副本新上传曾返回STORAGE_UNAVAILABLE，该失败证据保留。现经用户授权恢复colima与smartmaster，原Dev18894/API18893连接原MySQL/Redis，正常迁移115→116；原S3两旧对象HEAD/GET200且SHA匹配，原Dev录音与有声视频Omni r3全链通过。APFS克隆/恢复快照22:47生成并核验，但当前无法找到，位置及可恢复性仍在排查；原磁盘和所有原卷目前仍在，20:39 SQL及新建post-recovery一致性SQL备份均已验证现存，SHA/字节详见恢复记录。--rm测试容器自动移除后匿名卷以只读preserve容器保留；未新建bucket或用旧副本覆盖原库。恢复前原库核对为25历史/1用户/15消息/115迁移，随后新增3个QA用户和验收行，未执行删除或清空。不能据此推定当前无法定位的恢复快照仍可用。临时7个owned进程现已正常停止，`work/recovery-temporary-stop-proof.json`保留；原Dev18894/18893及AI/Media/realtime worker仍running。其他项目包括18882持续可用性不由本记录保证。

## 保留的真实未完成项

早期浏览器窗口消失的中断记录保留。后续已继续原Dev双浏览器技术验收：首轮音视频192帧及S3/frame成功，工作台poll关闭编辑器问题已修复，第二轮跨poll/挂断编辑、2元素标注ready预览和加入草稿通过，实际发送/客户UI可见和三S3证据关联及SHA均通过，浏览器独立新房gen2转接、目标RTP视频105帧、B接续并回复和原A写403通过；gen1/2房间cleaned/12席位释放通过，5条queued为15分钟墓碑补偿；空video修复后实际新通话挂断DOM/audio/video清零/全track结束、390px手机布局/历史200px/滚动composer可达/无溢出通过。P3同页最小Worklet probe已确认Ego工具暂停，产品超时及有序结束修复有47项定向回归/build，23:33真实浏览器39秒录音/技术画面问答、持久历史及17字草稿保留已通过，正常结束已到ended、8条技术track全停且17字草稿保留；之后恢复原生输入并通过正常UI发送，实际B2回答“文字恢复正常”；最终URL正确、runtimeExceptions0、error logs0、无Vite overlay。TaskSpace33已finish一次，保留原admin p1，关闭3个测试tab，A/B接受新单已UI置false。真人设备、安卓/现场、TLS/WSS/TURN、弱网/长时及ESP32声学回归未完成。B6真实来源登录/撤销和业务工具未配置，保持默认关闭，外部身份不能取得RTC或P3能力。模型辅助语义摘要、内部建议、真实设备/温度/报警/工单/知识检索与授权写入仍待实现或真实上游联调。详见 REMAINING_WORK.md。
