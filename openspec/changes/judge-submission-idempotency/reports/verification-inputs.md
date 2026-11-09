# 验收输入清单（manual）

日期：2026-11-09。风险 strict；执行上下文 `/root`。这是跨根人工核验所用的标准库文件清单，未调用或冒充项目 machine evidence 工具。

## 授权根和范围

- `B=/Users/lllyw/lyw-oj`：本变更全部规划、评审、验收报告，项目规则与所用技能，已确认用户计划，API/common/friend/judge 相关源码和测试，所有项目 POM，原始验收证据及本轮核验脚本。
- `F=/Users/lllyw/oj-fe-c`：全部 `src/`、`tests/`、`package.json`、`package-lock.json`、`vite.config.js`。

精确目录范围及按路径排序的完整条目见 [verification-inputs.json](/Users/lllyw/lyw-oj/openspec/changes/judge-submission-idempotency/reports/verification-inputs.json)。共 **460 个条目，其中 301 个普通文件**；逐项记录根代号、相对路径、类型、SHA-256 和可执行位，目录也纳入清单以发现新增或删除。枚举中没有需要另行适配的符号链接。

仅排除清单自身的两个输出文件，以及 `.git`、`node_modules`、`target`、`__pycache__` 目录。源码、依赖清单、任务状态、独立评审、结构化映射、日志、XML、截图和清理记录均未排除。文件指纹不涵盖 ACL、扩展属性或实时外部状态。

## 本次比较

原规划开始记录与持续约束核对通过；任务只改变完成标记，正文保持不变。192 个测试时源码条目与当前文件一致，独立代码评审登记的三份修改构建清单已另行比对。全部当前 Git 改动均在本清单覆盖范围内。

写入后重新枚举，当前目录、内容和可执行位与清单一致。实际比较时间和计数见 JSON 的 comparison；最终 Git 状态与收尾命令见 `B:.codex/judge-submission-idempotency/evidence/final-git-status.json`、`completion-checks.json`。

原 Maven 和前端构建命令中的隔离 cwd 已按计划删除，在验收映射中保留为历史位置，不伪称仍然存在；原命令、退出码和稳定证据仍可读取。后续复跑需要重新准备隔离环境。

后续复核命令（工作目录为后端根）：

```bash
python3 .codex/judge-submission-idempotency/manual_verification.py check
```

`check` 重新枚举并比较当前输入，不重新运行测试、不覆盖清单。发生变化时应评估对应验证是否需要重跑。没有执行归档。
