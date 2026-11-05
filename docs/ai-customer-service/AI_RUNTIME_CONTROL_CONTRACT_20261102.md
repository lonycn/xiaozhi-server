# A7 AI 运行准入与只读诊断契约

日期：2026-11-02。公开接口见 [独立 OpenAPI](./AI_RUNTIME_CONTROL_CONTRACT_20261102.yaml)。该功能默认关闭，由 `ai.runtime-control.enabled` 显式启用；它约束并发与请求次数，不是货币计费或总费用上限。

## 准入范围与默认限制

同一准入账本覆盖普通 AI run、实时会话、实时补充材料和普通回答朗读四类资源。新资源创建以及用户显式重试普通 run 各消耗一次小时请求预算；既有自动 attempt 恢复仍受原有 attempt/lease 上限约束，不作为新的准入次数。默认每用户最多 3 个活动资源、服务最多 32 个；滚动一小时每用户最多 60 次、服务最多 1000 次。配置项依次为 `ai.runtime-control.user-max-active`、`service-max-active`、`user-hourly-requests`、`service-hourly-requests`；本地 Dev 可通过 `ai-first-enable --runtime-control` 显式开启。

准入和对应资源创建在同一数据库事务内提交或回滚；已结束资源释放活动配额，但小时请求记录保留到窗口外。规范资源仍处于活动状态时，不能仅凭准入记录 TTL 断言任务已停止。历史未接入记录会按规范资源投影计入；**跨实例强准入仅适用于均接入四类准入接缝的写入端**，不能声称未升级的混合版本写入端也满足全局一致性。

超额返回真实 HTTP 429 与具体代码：`AI_RUNTIME_USER_CONCURRENCY`、`AI_RUNTIME_SERVICE_CONCURRENCY`、`AI_RUNTIME_USER_HOURLY_BUDGET`、`AI_RUNTIME_SERVICE_HOURLY_BUDGET`。资源归属冲突为 409 `AI_RUNTIME_RESOURCE_CONFLICT`；诊断连接池不可用或不支持分别为 503 `AI_RUNTIME_OBSERVER_UNAVAILABLE` / `AI_RUNTIME_OBSERVER_UNSUPPORTED`。失败不表示请求已进入执行，也不应自动无界重试。

## 只读诊断接口

`GET /api/v1/ai/runtime-control` 不接受 query 参数；身份由当前认证上下文决定，不允许调用方选择 actor。需授权 Bearer，返回 `Cache-Control: private, no-store` 与 `X-Content-Type-Options: nosniff`。开启会话功能而运行控制关闭时，服务返回真实 HTTP 404 `AI_RUNTIME_CONTROL_DISABLED`；非法 query 为 400 `INVALID_RUNTIME_CONTROL_QUERY`，无有效用户为 401。无有效会话或来源的访问依现有鉴权策略拒绝。

成功为 `{code:0,msg:"success",data: Snapshot}`。字段使用 **camelCase**；依当前 `WebMvcConfig` 的 `Long` 序列化约定，下述计数、额度和 `windowSeconds` 均是精确十进制**字符串**，不是 JSON number，客户端不要转换为不安全的 JS 浮点整数。

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "enabled": true,
    "windowSeconds": "3600",
    "limits": {"userMaxActive": "3", "serviceMaxActive": "32", "userHourlyRequests": "60", "serviceHourlyRequests": "1000"},
    "user": {"active": "1", "hourlyRequests": "4", "remainingActive": "2", "remainingHourlyRequests": "56"},
    "service": null,
    "usage": {"knownTokens": null, "tokenReports": "0", "unknownTokenReports": "0", "knownSpeechCharacters": null, "speechReports": "0", "unknownSpeechReports": "0"},
    "serviceLimited": false,
    "guarantee": "concurrency_and_request_admission_not_total_cost"
  }
}
```

`limits` 与 `user` 为当前 actor 可见。`service` 的实际全局活动数、小时请求数和剩余额度仅 `super_admin` 可见，其他用户为 `null`；`serviceLimited` 是全局当前是否触及并发或小时请求阈值的布尔提示，并不暴露其他用户明细。诊断不返回资源 ID、prompt、provider 密钥或其他用户用量；即使管理员，`usage` 也只统计当前 actor。

`usage.knownTokens` 合并当前用户普通 provider request、实时 response 和实时材料感知账本的已知 token；`knownSpeechCharacters` 单独累计朗读 provider 报告的 characters，两者单位不可混算。`tokenReports` / `speechReports` 为已有报告行数，`unknownTokenReports` / `unknownSpeechReports` 只统计**已有报告中值为 null 的行**，不包含完全未产生或未到达的报告。无已知值时对应 `known*` 为 null；这些数字只是已收到报告的下界，不是完整费用或 token 总量。窗口按各账本记录的 `created_at` 滚动一小时，不等同 provider 派发时间或账单周期。

## 观察连接与验证边界

生产 Druid 使用独立只读 observer 连接池，最多 2 个连接、最大等待 1000 ms、最多 2 个等待者、查询超时 3 秒；组件销毁时关闭。测试显式支持独立非池化 `DriverManagerDataSource`；未知数据源不回退复用主池，而是返回 503。此设计避免在外层事务持有主池连接时因诊断读取造成等待环，不代替 API、provider 或任务健康检查。

`work/ai-a5-a8-20261102/a7-runtime-proof.json` 记录了 15 项定向测试通过、零跳过：13 项真实 MySQL/InnoDB 持久化测试和 2 项控制器/实际 ObjectMapper wire 测试，涵盖并发、回滚、小时窗口、账本和 observer 连接边界。测试使用隔离的最小规范资源投影；最终全量测试、真实部署 HTTP 和端到端验收仍待根任务完成，不由该记录推断生产容量、App 或真实业务验收。
