# Singularity Iteration — SCEX 实验性研发分支

这是 [2132937983/Singularity-Iteration](https://github.com/2132937983/Singularity-Iteration) 的非官方 fork，用来公开 SCEX 已完成的修复、独立能源组件和行为验证工作。原模组由 Miopha / Zhus 开发；本分支由 Monarch / SCEX 维护，开发和审计使用了 AI / Codex 辅助。

**当前公开快照：R120（2026-09-17）。** 已同步独立电网、自动化、UU 扫描/复制、热能与动能设备、电气和液冷反应堆等已审核源码。完整模组来源、全玩法、整包和性能验收仍未完成；只公开冻结源码，不发布完整模组 JAR，也不能直接替换生产。最新范围和证据见 [本次同步记录](PUBLIC-SNAPSHOT.md)。

## 来源与同步方式

- 上游基础：`0.1.7.12-beta`，commit `7498151e1cde85b4183edd8a32d296e0acd76e5a`。
- fork 默认分支：`scex-development`，保留上游分支和本地研发历史。
- 保留既有上游及 R26 公开历史；本次叠加 R120 冻结归档中的审核源码，并移除 R103 退役 UU 源码组。开发工作目录保留原状。
- 目标：Minecraft 1.21.1 / NeoForge / Java 21，在现有 SI 基础上修复并独立替换部分能源实现；不是从零完成整个 SI，也不是 IC2 官方移植。

## 历史 R26 已完成的主要工作

| 范围 | 最新进展 |
|---|---|
| 电网核算与调度 | 连通分组、多源共享额度、接触历史与重载处理；保留有限随机近似及冷启动差异 |
| 独立变压器 | 已测四档升降压、多个输出面、逐包损耗、共享缓冲、自动红石模式、实体创建和保存恢复 |
| 特殊导线 | 独立探测/分流导线实体、红石信号、损耗、断开与延迟恢复、真实卸载 |
| 过压效果 | 限定场景中的方块掉落、实体伤害/击退和客户端声音；使用 Minecraft 原版爆炸声，音色不与 IC2 相同 |
| R26 机器升级 | 受控机器承压读取实际升级后的报价；修复爆炸后调度暂停提前消耗造成的一帧差异 |
| 保存与构建 | 发电→储能→加工→保存重启，以及独立模块和限定覆盖源码的归档重建 |

独立核心版本 `0.15.3-r24-experimental`；独立平台组件版本 `0.6.4-r25-experimental`。平台组件已包含实验性、显式启用的模组接入入口，仍依赖指定开发运行环境，不是完整 SI 替代品。根项目保留的 `0.1.7.12-scex.r5-dev1` 是历史标识，不是当前完整发行版。

## 历史 R25 / R26 验证范围

R25 冻结交付记录包括：22 组独立契约 / **1,932,483 项检查**，最终实体实现后的煤炭加工重启 **17,954 项**、固定变压器 **78,522 项**、双输出 **141,351 项**、实际客户端声音 **1,959 项**，以及相应独立源码归档重建。部分结果按未改动范围复用，详见 [R25 报告](SCEX-R25.md)。

R26 已测范围如下，计数为逐状态检查数，不是独立测试用例数量或覆盖率：

| 检查 | 已测范围与结果 |
|---|---|
| 静态升级承压 | 四种基础机器，0～3 变压器升级，32/128/512/2048 EU；64 场景 / 19,008 检查 |
| 动态插入与拆除 | 混合组及两个新时刻前向验证组，共 160 场景；14,160 + 14,840 + 14,840 检查 |
| 加工、容量与动态边界 | 超频/储能升级、断电、堵塞、换料和批量边界；54 场景 / 382,731 检查 |
| 真实保存重启 | 12 台升级机器，2 个 JVM / 1,185 tick / 128,825 检查 |
| 限定源码重建 | 8 个源码归属、21 个生成类，离线重建逐字节相同 |

详见 [R26 报告](SCEX-R26.md)、[研发路线图](SCEX-ROADMAP.md) 和 [本次同步记录](PUBLIC-SNAPSHOT.md)。本次 GitHub 同步核对冻结输入、源码对应关系和公开内容，复用已有游戏验收，不重复启动测试实例。历史报告的“不发布/不推送”描述其当轮交付状态；维护者现已要求同步本源码快照。

## 已知差异与后续工作

声音采用 Minecraft 原版素材；连接历史与掉落随机性为有限独立近似；冷世界绝对 tick 的首次提交时刻仍有差异。不能据此宣称 IC2 全行为等价或生产 TPS 提升。

最新限定完成项及未完成项见 [R120 同步记录](PUBLIC-SNAPSHOT.md)。各完整工作包仍需闭合剩余边界。GUI、多人、旧版本世界、全配方、极端升级数、代表整包和性能验收仍开放。详细限制以对应报告为准，后续反例和修正优先于较早结论。

## 许可、独立实现边界与发行门禁

保留上游 [Apache-2.0 LICENSE](LICENSE)、[NOTICE](NOTICE) 以及已有 MIT 文件头和署名；原作者及其他贡献者权利不因 fork 转移。新增独立组件按目录内 Apache-2.0 声明提供。

新组件以 IC2 Experimental 2.8.222-ex112 成品的普通游戏交互观测为参照，不使用 IC2 源码、IC2 API、反编译实现或来源待澄清旧实现作为模板。该说明只适用于本地独立组件，不能证明全部继承源码和素材已经完成来源清查。

[来源审查策略](gradle/source-provenance-policy.json) 保留尚未解决的来源范围；R120 检查点仍有 8 个来源组；这是保守审查边界，不是抄袭认定，清单外内容也未全部清查。完整模组打包/发布门禁保持启用，不通过改名、删注释或改布尔值绕过。

历史 R5 SI JAR 是部分隔离试验的私有运行输入，不是来源清查通过的发行版。本次不包含该 JAR、IC2 参考成品、依赖副本、测试世界或私有运行配置，也不发布可直接安装的完整模组。源码公开不等于解除完整模组发行门禁。

## 构建入口

使用 JDK 21。独立核心的无外部依赖构建见 [模块说明](cleanroom-energy/README.md)；依赖已缓存时也可运行：

```powershell
.\gradlew.bat --offline --no-daemon --max-workers=2 :cleanroom-energy:check :cleanroom-energy:jar
```

[平台组件](cleanroom-minecraft/README.md) 使用公开 Minecraft / NeoForge 接口。实际验证 NeoForge 21.1.218；平台声明 Minecraft `[1.21.1,1.22)`、NeoForge `[21.1,)`，不表示所有声明版本都已测试。完整根项目 JAR 任务受来源门禁阻止，这是当前预期行为；`BUILD SUCCESSFUL` 或 `test NO-SOURCE` 不能替代功能验收。

## English summary

Unofficial SCEX research fork of Singularity Iteration by Miopha / Zhus, maintained with AI/Codex assistance. The current snapshot is **R26 build 21**, including the scoped R25 stage-two acceptance and the first stage-three machine-upgrade batch. It preserves the development history and imports frozen, previously uncommitted source inputs without modifying the active development checkout.

Independent transformer/special-cable integration, selected overload effects, upgraded-machine voltage handling and save/restart scenarios have scoped evidence. Known differences include the Minecraft explosion sound, approximate random behavior and cold-start timing. This is not a complete release, an official IC2 port, a claim of full behavioral equivalence, or a production-ready replacement. Full-mod provenance and publication gates remain active; no full-mod binary is released. R27 work in progress is excluded.
