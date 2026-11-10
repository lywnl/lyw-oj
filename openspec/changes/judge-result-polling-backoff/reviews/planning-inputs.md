# 规划输入清单

核验方式：manual。A 为规划根 `/Users/lllyw/lyw-oj`，B 为本次获准修改的前端根 `/Users/lllyw/oj-fe-c`。

目录哈希登记直接子项名称与类型，文件哈希覆盖实际内容与可执行位。仅 tasks 的完成标记在持续约束中归一化；参考代码允许在实施开始后按任务修改。排除不相关源码、Git 内部数据、依赖目录与本清单自身；B 的目录只用于发现新增/删除，不将无关工具文件作为本次实现范围。

| 分组 | 根与路径 | 类型 | SHA-256 | 可执行 |
|---|---|---|---|---|
| plan | A:openspec/changes/judge-result-polling-backoff/.openspec.yaml | file | dadf913140cf9e5ce46cec70f88994f5619b0ffd3f79239c83be129fd2c8309a | False |
| plan | A:openspec/changes/judge-result-polling-backoff/proposal.md | file | e45c5f34b30727c69000cec444e3439771d1175b725f62e85c25a0d92d3d1c76 | False |
| plan | A:openspec/changes/judge-result-polling-backoff/design.md | file | 6b00d7b56ead23d9a16630a1641cb2b7faf84d42c530af4890d16d17f84be67d | False |
| plan | A:openspec/changes/judge-result-polling-backoff/tasks.md | file | b931f1008dc8bd9dcd7c980a0e1ed9f0a4393a63fa3b0f7eabf13f4345975873 | False |
| plan | A:openspec/changes/judge-result-polling-backoff/specs | directory | 7fb5af9ac6b266e2cb3a083d433b821be1e79952c900e62055174b766deadb0a | True |
| plan | A:openspec/changes/judge-result-polling-backoff/specs/judge | directory | aa9b8fa68020f8feb0adc0ace7fca2c75254ef49406a4d5cfa39a6414aa56ae6 | True |
| plan | A:openspec/changes/judge-result-polling-backoff/specs/judge/submission-results | directory | e1faefe581ee308c6e24a0eb7af36921365c9cb5db593b3a95d0599c161d8cb2 | True |
| plan | A:openspec/changes/judge-result-polling-backoff/specs/judge/submission-results/spec.md | file | e4866b59c98b62b021ad577fad6a1a8248afd7f7dd35386b9c3fa3c7f260a89f | False |
| plan | A:openspec/changes/judge-result-polling-backoff/reviews/change-review.md | file | c1c78e195575319495a151cf88a8038d0f0dbfe87974614198267821243e8b74 | False |
| source | A:AGENTS.md | file | 20357a5428a4639631d2cccf23c6b9eb47b615960acc1f30db1a77c7f4bb4409 | False |
| source | A:openspec/config.yaml | file | e8826c219d6cfa70e6c4732ad39e124354a60b28317b9b818c41250144d33bb7 | False |
| source | A:.agents/shared/openspec/workflow-policy.md | file | 8a324454d91d27dda2fbf140198cbbc7413c3ca3fd9a1af75553b12517b60d1d | False |
| source | A:.agents/shared/openspec/project-context.md | file | 7fcd62d9e0c8257044f34ab08dc2c1a5830d427dfd8e7992c06532375ee13d71 | False |
| source | A:.agents/shared/openspec/verification-evidence.md | file | 6061d192eefbb4b0e8207b36d78a0ebb1aef7904f5d9caf6a5fbd52cb600cd19 | False |
| source | A:.agents/shared/openspec/review-checklist.md | file | f9b0b88624f17a916b52c027ec9490abc64484ad935b33dc24df36a33a76a3fa | False |
| source | A:.codex/leetcode-rea/20261110-c5e50c0a/deep-dive-3d540dfe/findings.md | file | 1cf55672d44dc2d6c1d56dcc65987f7a623a7e4fa52fb2b3a5a1d149c9f55157 | False |
| source | A:openspec/changes/judge-submission-idempotency/specs/judge/submission-results/spec.md | file | d390a703a0753bf77980fc4266b7a1d73d40d36ed80ff1149cfc47239d07e975 | False |
| source | B:package.json | file | 421968d14bee510dbcf117664a576df4e0a48262059361a8beb8209c3bbdae88 | False |
| source | B:package-lock.json | file | 893b6ae02d963e48735f754a4a7691ed47422028804e1b122e983122a4abcdec | False |
| source | B:vite.config.js | file | b015398c60f271953dede876fcd2931ebac6ba8e3744e783ef52e9239b6b073e | False |
| source | B:src/views/Answer.vue | file | 49fcefd9f6365b8a34340a4c3505ff3d569a85a06f9843a7a5bd04f81fc70cc8 | False |
| source | B:src/apis/question.js | file | 63cc79b773ac1f1181dd2e68f8a6d2fc07aa687ffae79f1c494bcbbe8373cfb9 | False |
| source | B:src/utils/request.js | file | 2b7a0115238261391fc81bb885832455c86585a5fc2821bbeb2720db99c614d2 | False |
| reference | B:src/utils | directory | f159a7a8826e5fa8c1c33d8f4c320b176cf217345d76c8bb59122a61aec10c09 | True |
| reference | B:tests | directory | 28069fac997d643f3d990312ffaeb736f86f8e43408c8e455099214d0d28dee9 | True |
| reference | B:src/utils/judgeResultPoller.js | file | dd455438c86b8644320e7438ecf7b0230e9401bd7628df5685f1b18a84168ab7 | False |
| reference | B:tests/judgeResultPoller.test.js | file | e8cbad81e370c7b010832595358bff4765098ffa45c26043c6964db560e5e9a5 | False |
