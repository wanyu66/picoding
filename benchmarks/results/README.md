# 结果目录

`phase1-*` 仅为静态审计证据，不是压测结果。每次新建，不覆盖旧目录：

- `environment.json`：Node os API实际环境；包含采样时间。
- `git-state.json`：HEAD和工作区文件状态（没有diff内容）。
- `source-manifest.csv`：相关模块源码、测试、构建/配置及脚本SHA-256；不包含配置正文、密钥或个人业务数据。
- `checks.json`：四份文档与目录、代表性源码入口是否存在、文档相对链接是否有效。通过仅表示初始化结构有效。

后续性能/故障实验按run_id单独归档，遵循 metrics-definition.md 中manifest、逐请求/事件、监控、summary、cleanup要求。PASS/FAIL/BLOCKED/SKIPPED分别记录；没有执行的场景状态NOT_RUN、值null、原因明确。

不得把历史报告复制后当作新测量，不输出只有结论没有原始JSON/CSV的简历数据。原始数据归档前必须脱敏；不保存真实对话、个人资料或认证信息。
