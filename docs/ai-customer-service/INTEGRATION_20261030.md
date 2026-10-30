# 2026-10-30 整合交付记录

Dev 分支整合了非实时 Omni 原始音轨、P3 实时 AI、B5 真人音视频及换房转接、B6 默认关闭的来源身份、内部交接摘要和 Vue3。原始18节规划文件完整保留。下述技术样本、运行副本和现场验收分别记录。

## 最终检查

- Java 主工作树全量457项通过，零失败/错误/跳过，产出独立Spring Boot JAR。所有持久化测试使用新隔离库，已有及失败测试库保留；日志 `work/integrated-java-r2.log`。
- Vue3：10项契约、368项单元/组件测试通过；6语言各1840 key一致，生产构建通过。最后只调整六语言画面使用提示，再次i18n和构建通过。
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

Docker VM在本轮出现真实存储I/O错误，影响多项目。原18894后端连接中断；没有全局重启、清库、删除卷或覆盖原数据。更新前完整备份为 `dev/.runtime/backups/before-integrated-media-20261030T123914Z.sql`，303525字节，SHA256 `8e71e06ee7bc986f3e5fac8e7f540d8ba5c30ee69ee60a39d5a8d4000bbd6eec`；含25条旧设备历史、1用户、11条统一消息、112条迁移。

备份只恢复到一个新的隔离native库，保留原卷。恢复副本Vue3为127.0.0.1:18895、API18901、独立实时bridge18902；页面标“20:39备份”。当前运行最终整合JAR，迁移增至116，旧历史25和用户1保持不变。备份后的原Dev技术测试行仍在原卷，没有用副本覆盖。副本不是原库当前最新状态；私密凭据和配置仅保存在本机权限受限目录，不入库。

S3副本的新上传目前返回STORAGE_UNAVAILABLE，需恢复对应存储通路后再验收；不以新建bucket、替换文件或改全局策略绕过。原18882源与卷未修改，同一Docker故障下不承诺其持续正常。Docker恢复后需用最终JAR定向recreate本项目API和worker，核对现库状态，不能用初始化替代。

## 保留的真实未完成项

浏览器验证窗口消失，按ego-browser使用规则停止并询问是否重开，尚未回复；未用另一窗口或Playwright绕过。真人设备、安卓/现场、TLS/WSS/TURN、弱网/长时及ESP32声学回归未完成。B6真实来源登录/撤销和业务工具未配置，保持默认关闭，外部身份不能取得RTC或P3能力。模型辅助语义摘要、内部建议、真实设备/温度/报警/工单/知识检索与授权写入仍待实现或真实上游联调。详见 REMAINING_WORK.md。
