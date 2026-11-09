# 规划输入清单（manual）

风险 strict；元数据 x-workflow-profile / x-verification-mode 分别映射 workflow_profile=strict / verification_mode=manual。

B=/Users/lllyw/lyw-oj；F=/Users/lllyw/oj-fe-c。完整排序清单见 manual-planning-snapshot.json；由本轮标准库助手逐根枚举类型、SHA-256、可执行位生成，并人工核对归属与排除范围，不冒充 machine 助手结果。

planning 为规划与评审报告；constraints 为持续约束；reference 为获准修改的代码、测试和依赖。任务只归一化 checkbox 完成标志。缺失的新增测试目录按 missing 登记。排除 Git、node_modules、target、Python 缓存，未扩大授权根。符号链接若出现将停止并逐项人工核对。

当前无符号链接适配项。首次修改前必须比较全部三类清单并在原 JSON 登记 implementation_started；之后只比较 planning/constraints，参考代码改动交由最终验收。
