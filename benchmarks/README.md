# Paicoding 简历量化验证

最新修复记录：[2026-09-27 测试问题修复、实测结果与剩余失败](results/fixes-20260927/benchmark-summary.md)。包含ES异常处理、排行榜夹具、点赞并发和聊天背压；旧失败证据保留，不能视为全部业务通过。

第一阶段：代码审计与测试规划（2026-09-24）。只新增本目录文件；不修改业务逻辑、生产配置、接口或数据库结构，不启动应用、容器或模型请求。

- [代码定位与测试方案](test-plan.md)
- [指标定义与基线规则](metrics-definition.md)
- [环境与复现命令](environment.md)
- [原始证据与结果规范](results/README.md)
- [第二阶段：文章数据生成、导入和批次清理](article-import.md)
- [第二阶段实测报告：1万篇最终一致性通过、首次等待超时](results/article-import-report.md)
- [Outbox / RabbitMQ：代码定位、八类故障场景与复现命令](outbox-test-plan.md)
- [Outbox / RabbitMQ：实测结果](results/outbox/benchmark-summary.md)、[文件与执行记录](results/outbox/phase-close.md)

## 本阶段结论

后续阶段新增：[ES 代码定位与基线计划](es-test-plan.md)、[运行与清理命令](es-operations.md)、[ES 实测汇总](results/es/benchmark-summary.md)、[相关性待人工评测](results/es/relevance-report.md)、[降级故障报告](results/es/fallback-report.md)、[真实重建方法与 Alias 验证](results/es/alias-rebuild-report.md)。这些报告区分引擎组件、应用链路与未验证项目，不将历史审计阶段的 N/A 当作后续实测结果。

搜索、Outbox、排行榜、AI 和 STOMP 均有实际实现，但“存在实现”不代表运行验证通过。ES 写入失败可被吞掉、Redis 投影跨事务、聊天重放有容量及 TTL 边界，必须通过故障测试验证。旧 `/chatgpt` WebSocket 配置注解被注释，不能作为当前在线入口。

已有 Node HTTP 压测、搜索相关性评测、Outbox/排行榜隔离故障测试和模拟模型流测试，优先复用。历史 `docs/performance/` 报告仅作线索，未在本阶段复测，不引用其中数字作为新结论。相关性人工标注未完成，真实模型性能、跨节点 STOMP 恢复能力均为 N/A。

当前 HEAD 为 `4095f74d6ef338ddc5421eb4804f2c40603b1631`，工作区已有大量修改及未跟踪文件。HEAD 不能唯一描述本次审计版本；需同时使用本阶段文件哈希清单。哈希仅用于辨认版本，后续正式实验还需保留脱敏后的源码快照或固定提交。

## 目录约定

当前交付四份规划文档、`scripts/audit.mjs`（只读源码审计及文档检查）和 `results/`。后续测试专用配置、合成数据分别放 `config/`、`data/`，运行脚本放 `scripts/`，本阶段不生成可直接连接业务服务的配置。

从项目根目录安全复现初始化检查：

```powershell
node benchmarks/scripts/audit.mjs
```

脚本仅读取仓库和本机 Node 环境；创建新的 `results/phase1-<UTC时间>/`，保存环境 JSON、Git 状态、源码 SHA-256 CSV 和检查 JSON。不读 `.env`、不复制配置内容、不联网、不写业务目录。重复运行不会覆盖旧结果。

## 后续实验门槛

1. 固定源码、依赖版本、数据快照、随机种子、时区与配置摘要；隔离实例、数据库、Redis 和 RabbitMQ，不以“localhost”作为数据安全证明。
2. 每轮先恢复同一合成数据快照，记录实际规模、并发、预热、测量和排空时间。通过功能校验后再采性能数据；失败原样保留。
3. 每次只改变一个因素，A/B 交替，至少三轮。没有可比较基线只报告容量或绝对耗时，不写“提升 X%”。
4. 故障测试只操作本次专属资源。现有故障工具有 DROP TABLE、FLUSHDB 和删队列行为，必须先核验隔离归属；共享实例禁止运行。禁止删除已有业务数据。
5. AI 默认使用本地确定性模拟服务。真实付费请求必须另行确认，并单独记录模型、预算与请求数。

## 阶段结束记录

- 实际修改：仅 `benchmarks/` 新增文档、审计脚本及审计结果。
- 执行：源码读取、Git 状态/版本检查、工具版本探测、`node benchmarks/scripts/audit.mjs`；实际检查结果见本次 `checks.json`。
- 测试：本阶段不执行业务单测、集成测试或压测；不能表述为这些测试已通过。
- 测量：仅环境信息和文件数量/哈希；吞吐、延迟、可靠性比例、相关性提升均为 N/A。
- 剩余：隔离依赖及完整数据恢复工具尚未统一；人工标注、真实 STOMP 链路和 ES 同步失败验收待完成。
- 下一阶段：先完成隔离与清理验证，再运行选定离线单测、模拟模型测试和故障验收，最后建立性能基线。
