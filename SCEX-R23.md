# R23：独立变压器账目组件（阶段 2 进行中）

R22 的原版黑盒证明恢复时序在运行之间和同一运行内都可能变化。R23 将账目计算与调度选择分离，新增零外部依赖的 `TransformerAccounting`；不读取 IC2 源码、API、反编译实现或来源待澄清 SI 实现。

## 实现

调用者明确传入 `INPUT_FIRST` 或 `OUTPUT_FIRST`。组件从 tick 初始缓冲确定输出资格，按已测单接收器电包批次规则计算实际出电，再按相应先后关系计算可收电空间。支持实测容量、整包源端资格、缓冲不足、无接收器、接收器满电及单接收器部分需求。接收器可因已测批次边界超过名义容量；变压器缓冲和源端必须保持合法范围，计算溢出明确拒绝。

这是一个损耗为零的单电源→变压器→单接收器账目组件，不是调度器或完整电网。过压效果、多面分配、拓扑变化、模式切换及 SI 接入未实现于该组件。没有把“任选一种顺序能解释结果”说成预测了原版顺序。

## 验证

从 11 组已完成成品观察中导出 15,200 个数值单步状态，逐项验证原始记录 SHA-256，并将命令引起的预充/清空作为该步外部输入处理。数值夹具 SHA-256 为 `b2d7861785762dca3135390b57fc812bb65d9fc2a5b814448ee8ba59fd74bc76`；来源清单随夹具保留，不含原版代码或二进制。

15,200 个观察结果全部属于组件的两种账目结果之一：24 笔仅匹配输入先行，14 笔仅匹配输出先行，15,162 笔两者相同。新增契约 106,409 项检查通过，包括能量守恒、容量边界、无效输入与整数溢出。这是单步结果覆盖检查，不是顺序、完整轨迹或概率分布的盲测验收。

Java 21.0.12.8（Temurin）、Gradle 9.2.1，离线执行：

```text
gradlew.bat --offline --no-daemon --max-workers=2 --console=plain :cleanroom-energy:transformerAccountingContractTest
gradlew.bat --offline --no-daemon --max-workers=2 --console=plain :cleanroom-energy:check :cleanroom-energy:jar
```

两次均退出 0。完整核心 16 组契约、1,570,795 项检查通过，外部/旧实现依赖均为 0。日志见 `evidence/r23/transformer-contract-01.log`、`core-check-build-01.log`。

## 产物与边界

独立组件 `scex-independent-energy-0.14.0-r23-experimental.jar`：52,094 字节，SHA-256 `ff4c7074d7952cf2d4efe3fa5058fd690a99813fd7d919ef816ccfea621d33b7`。不构建、发布或部署完整 SI 模组，完整来源门禁保持未通过。旧 R22 隔离运行仍对应旧冻结组件；不能把新库文件替换进去后沿用旧运行结论。

本轮未运行新的 Minecraft 集成或性能验收。下一步检验调度选择、批次跨接收器行为以及模式切换，再扩展 SI 接入；实体爆炸、特殊导线、完整持久化和阶段 3～5 仍未完成。
