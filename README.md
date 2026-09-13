# Singularity Iteration — SCEX 实验性研发分支

这是 [2132937983/Singularity-Iteration](https://github.com/2132937983/Singularity-Iteration) 的非官方 fork，用来公开 SCEX 本地已完成的修复、独立能源组件和行为验证工作，便于阅读差异与后续上游协作。原模组由 Miopha / Zhus 开发；本分支由 Monarch / SCEX 维护，开发和审计使用了 AI / Codex 辅助。

**当前公开快照为 R15，属于持续研发成果，不是完整模组发行版，也不能直接替换生产服中的 SI。** 本次只同步源码与说明，不发布完整模组 JAR。研发目录中的 R16 尚在进行，本快照不包含其未提交改动。

## 与上游及 IC2 的关系

- 上游基础：`0.1.7.12-beta` 分支，提交 `7498151e1cde85b4183edd8a32d296e0acd76e5a`。
- 本地完成点：`c1a68e27a0d4a263ee793f3f33c589daf01fc87a`，保留 24 个本地研发提交；fork 默认分支为 `scex-development`，上游分支保留。
- 目标环境：Minecraft 1.21.1、NeoForge、Java 21。这是在现有 SI 源码基础上持续修复与独立替换部分能源实现，不是从零完成整个 SI，也不是 IC2 官方移植。
- 新独立组件以 IC2 Experimental 2.8.222-ex112 成品的普通游戏交互观测为行为参照；开发不使用 IC2 源码、IC2 API 或反编译实现，也不将来源待澄清的旧实现作为模板。
- 上述方法说明只适用于本地独立组件，**不能替整个继承仓库证明来源已全部清查**。参考 IC2 成品、测试世界、运行日志和私有配置不随本次源码发布提供。

## 已完成什么

早期工作包括构建与编码/资源问题修复、源码构建入口和依赖锁定、等级与能量持久化相关回归。R6 起保留完整模组发行门禁，逐步开发并验证独立能源实现：

| 部分 | 当前成果 |
|---|---|
| `cleanroom-energy` | 仅依赖 JDK 21 的能源核算、路径/拓扑、登记失效、损耗、部分熔断与过压场景、延迟登记和接收顺序组件；R15 版本 `0.8.0-r15-experimental` |
| `cleanroom-minecraft` | 基于公开 Minecraft / NeoForge 接口的平台生命周期适配；版本 `0.3.0-r13-experimental` |
| SI 接入层 | 已测机器报价和余额共同提交，煤炭发电→储能→加工→保存重启等受控流程 |
| R15 最新修复 | 将接收顺序改为符合已冻结观测的反向循环，修正部分容量下总账正确但接收分配不符的问题 |

模块 JAR 是实验组件，不是可单独放入 `mods` 的完整 SI。根项目仍保留历史 `0.1.7.12-scex.r5-dev1` 版本标识，它不是当前完整发行版。构建/测试使用的 NeoForge 21.1.218 不代表所有其他版本已验证。

## 已有证据与未完成范围

R15 交付记录：十组独立契约累计 **1,377,746 项断言**通过；真实 SI 的两组部分容量回归共 **3,600 笔分配向量**通过；煤炭发电到保存重启回归通过 **17,954 项流程/状态检查**，另有 **22 项真实提交边界检查**。这些计数属于对应组件和受控场景，不是整个模组所有功能的验收数量。本次 GitHub 同步复用该冻结交付记录，没有重新启动游戏测试。

详见 [R15 报告](SCEX-R15.md)、[研发路线图](SCEX-ROADMAP.md)、[独立能源组件](cleanroom-energy/README.md) 和 [平台组件](cleanroom-minecraft/README.md)。较早报告描述各自当时的状态；后续发现的反例和修正优先于旧结论。

尚未完成所有电压和特殊导线、复杂/重载拓扑、多源调度、混合损坏、跨维度时序、全玩法、真实客户端/多人/旧世界兼容及代表整包性能验收。不宣称与 IC2 全行为等价，不宣称已提升生产 TPS。

## 来源、许可与完整模组发行门禁

保留上游 [Apache-2.0 LICENSE](LICENSE)、[NOTICE](NOTICE) 以及文件中已有的 MIT 署名和许可。原作者及其他贡献者权利不因 fork 而转移；本地新增独立组件按其目录内 Apache-2.0 声明提供。

[来源审查策略](gradle/source-provenance-policy.json) 仍标记 65 个文件的保守待澄清范围，其余继承源码和素材也没有全部完成来源审查。这不是抄袭认定，也不把根许可证视为全部素材独立来源的证明。

完整模组打包/发布门禁保持启用。此次公开研发源码不解除门禁，不把历史 R5 完整 JAR 当作已清查发行版，不通过更改布尔值、删除注释或重命名绕过。历史报告中“不自动发布”的说明是当轮交付边界；本次经维护者要求公开这个已完成源码快照，未进行生产部署或向原作者发送消息。

## 构建入口

使用 JDK 21。独立核心可按 [模块说明](cleanroom-energy/README.md) 使用 PowerShell 脚本构建，或在依赖已缓存时运行：

```powershell
.\gradlew.bat --offline --no-daemon --max-workers=2 :cleanroom-energy:check :cleanroom-energy:jar
```

完整根项目的 JAR 任务受来源门禁阻止，这是当前预期行为。平台与 SI 集成还需要声明的 Minecraft / NeoForge 依赖及对应测试环境；单纯 `BUILD SUCCESSFUL` 或 `test NO-SOURCE` 不表示完整功能验收。

## English summary

An unofficial SCEX research fork of Singularity Iteration by Miopha / Zhus. This branch preserves the completed local R15 history based on upstream `0.1.7.12-beta`, with AI/Codex-assisted fixes, independently authored Java 21 energy components and controlled behavioral validation. It is **not a complete release, an official IC2 port, or a production-ready replacement**. R16 work in progress is excluded from this snapshot.

The new components use ordinary observations of a supplied IC2 Experimental binary, without IC2 source, API or decompiled implementation. This does not certify the provenance of all inherited SI code or assets. Original licenses and attribution are retained, unresolved provenance boundaries and the full-mod packaging gate remain in place. No full-mod binary is released here; see the linked reports for evidence and limitations.
