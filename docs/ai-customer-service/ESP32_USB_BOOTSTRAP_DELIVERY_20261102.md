# ESP32 首板 USB 迁移与实机运行

后续状态：当晚已通过 USB 安装 2.5.6 声音与语音控制修复版，并收到真实 healthy 回执。当前版本及回退分区以 [声音与退出修复](ESP32_AUDIO_VOICE_CONTROL_20261102.md) 为准；下文保留 2.5.3 首板迁移历史。

2026-11-02，用户授权直接实现首板迁移、发布局域网候选并烧录当前 USB 主板。本次使用已经冻结的 2.5.3 LAN 固件，不改变板型引脚、资源或生产发布地址。

## 已运行的链路

- 实物：ESP32-S3，16 MiB Flash、8 MiB Quad PSRAM、128×32 OLED。
- 固件：`bread-compact-wifi-128x32-ai-lan`，2.5.3，源码 `e14ec7e635fc53226dad1935ce5f0cd20042bb64`。
- 应用 SHA256：`a7c860a0942926ff337d6c0d1e474a8f298a10eca0df789b491d6548339985c0`。
- 保留软件 MAC `AA:BB:CC:DD:EE:FF`、原 UUID、Wi-Fi 与三条“业务助手 / 你好总管 / 总管总管”唤醒资源；eFuse 不修改。
- 独立网关：`ws://192.0.2.10:19180/xiaozhi/v1/`。
- 设备 OTA：`http://192.0.2.10:19183/xiaozhi/ota/`。
- 独立 Manager：`http://127.0.0.1:19193/xiaozhi`，Redis 19192。

这是明确的局域网开发部署，使用原 Dev 的只读 SQL 快照建立独立 schema `xiaozhi_usb_live_20261102_171956`。原 Dev API/Vue、原数据库及原 18880/18883 设备服务保留；没有清库、删卷或重新初始化原库。新设备会话写入该独立 schema，不自动合并回 Dev。

发布 ID `ceb8c780661445d19849292b6356ede1`；最终指派 ID `2b5fe1909e714a1f8b1c2fb857d79559`。已收到物理设备的 `booted → healthy` 回执，HTTP 均为 204。新网关实际读取设备 MCP 状态成功，音量为 70。

## 首板安装机制

`dev/esp32_usb_bootstrap.py` 提供明确分开的 `stage / activate / retry_boot / rollback` 操作，需要操作者准备的私有配置和管理员凭据。

1. 校验已发布包、有效指派、实际 eFuse MAC、完整 16 MiB 备份、旧应用、分区表和现有资源。
2. 在 NVS 活动页追加 `release_ota` 元数据，使用 ESP-IDF 官方解析器逐项比较；原 59 项非升级配置全部保留。空间不足时失败，不重建 NVS。
3. 仅写入非活动 `ota_1`（`0x410000`）和该 NVS 候选；整片读回与预期逐字节比较，覆盖所有未修改区域。
4. 管理员通过显式开启的 LAN-only `usb-bootstrap` 接口提交实际读回证据。它记录来源为 `administrator_usb_readback`，不伪造设备下载或设备回执。证据不可替换，暂停、重指派或撤权后拒绝旧操作。
5. 校验通过后只写第二 OTA 选择扇区 `0xe000`，状态为 NEW。保留第一扇区中旧 `ota_0` 的 VALID 状态、旧应用、bootloader、分区表和原资源。
6. 复位后持续持有串口至设备确认健康，再交回 USB 日志桥；未确认的镜像仍受原有启动回退保护。`rollback` 只恢复保存的 NVS 与 OTA 选择数据，不覆盖应用和资源。

禁止把包含空白 NVS 的 `first-flash.bin` 直接写入本板。新固件按设计关闭 USB 屏幕镜像及 USB 文字输入；USB 仍可读取真实启动日志，AI 通讯使用上述局域网网关。

## 本次修复

- 管理端新增默认关闭的 `esp32.ota.usb-bootstrap-enabled` 和管理员 USB 证据接口；正式部署不接受该 LAN 引导入口。
- 兼容已有 MAC 形式的设备数据库主键，无需删除设备或重新绑定。
- 在固件指派之前调用网关的 canonical 授权初始化。首次连接建立授权记录不再作废刚创建的固件指派；旧失效指派保留，使用新的有效指派恢复。
- 修复引导工具私有证据文件写入，并增加权限回归。
- 设备入口代理只记录回执状态与受限错误码，不记录凭据、下载票据或设备正文。
- SQL 快照中的六个触发器原 definer 在独立本机 MySQL 上不存在。仅修复独立 schema 的 definer，核对触发器名称和正文完全一致；原 Dev 不变。

首次启动曾进入未确认镜像回退，实际观察到旧 2.5.2 可启动及 OTA 镜像 ABORTED。随后持续串口监控完成 2.5.3 自检。另发现并修复了首次授权初始化导致健康回执 409 的真实问题，最终由设备自己成功报告 healthy。

## 证据与维护

本次私有工作目录由 `/path/to/user/code/esp32/.local/usb-bootstrap-path.txt` 指向：

- [最终目录](/path/to/user/code/esp32/work/esp32-usb-bootstrap-20261102-171736)
- [真实 USB 读回证据](/path/to/user/code/esp32/work/esp32-usb-bootstrap-20261102-171736/usb-install-r2/usb-readback-proof.json)
- [实际启动日志](/path/to/user/code/esp32/work/esp32-usb-bootstrap-20261102-171736/usb-install-r2/retry-boot-boot.log)
- [设备 MCP 状态证据](/path/to/user/code/esp32/work/esp32-usb-bootstrap-20261102-171736/physical-mcp-status.json)
- [最终设备及回执证据](/path/to/user/code/esp32/work/esp32-usb-bootstrap-20261102-171736/final-live-proof.json)
- [最终 Java 测试](/path/to/user/code/esp32/work/esp32-usb-bootstrap-20261102-171736/java-tests-build-r3.log)

目录包含 Wi-Fi、设备凭据、SQL 与 Flash 备份，仅留在本机私有目录，不上传或打包公开分发。

服务启停使用 `dev/esp32_canary_runtime.py start|status|stop --runtime <上述目录>`。该工具验证自有进程身份，只管理本 canary，不写固件、不创建数据库、不停止原生服务。主板已安装新版本时，停止 canary 将使其暂时离线，重新启动服务后再验证重连。

联调配置把无语音空闲窗口设置为 1800 秒，避免默认 120 秒（另加连接关闭宽限）在现场等待期间频繁回收连接。此设置只落在本 canary 的私有配置，未修改原生服务或生产默认值。

## 验证边界

- 新增 Python 引导测试 4 项通过，含官方 NVS 生成/解析、原配置保留、OTA NEW/VALID 状态和私有证据权限。
- 最终 Java 固件套件 10 项通过、0 失败/错误/跳过，包含首次网关授权不使指派失效的回归。使用独立新测试库，全部保留；不叠加中间重跑次数。
- 候选包离线校验、真实 HTTP 下载 SHA256、USB 全片读回、2.5.3 启动、真实协议 hello/MCP、设备 booted/healthy 回执及只读状态命令通过。
- 真人三词唤醒、喇叭听感、连续声学使用仍待现场反馈；模拟器与 USB 读回不替代这些结果。
- 未实施断电破坏、坏包、完整 OTA 升降级或公网 HTTPS/WSS 验收，不将本次 USB 引导称为生产发布或完整 OTA 回退验收。
