# R23：独立变压器账目组件（阶段 2 进行中）

R22 的原版黑盒证明恢复时序在运行之间和同一运行内都可能变化。R23 将账目计算与调度选择分离，新增零外部依赖的 `TransformerAccounting`；不读取 IC2 源码、API、反编译实现或来源待澄清 SI 实现。

## 实现

调用者明确传入 `INPUT_FIRST` 或 `OUTPUT_FIRST`。组件从一次账目轮次的初始缓冲确定输出资格，按已测单接收器电包批次规则计算实际出电，再按相应先后关系计算可收电空间。支持实测容量、整包源端资格、缓冲不足、无接收器、接收器满电及单接收器部分需求。接收器可因已测批次边界超过名义容量；变压器缓冲和源端必须保持合法范围，计算溢出明确拒绝。

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


## 冻结组件的前向验证

在不修改归档 JAR 的前提下，直接调用其 Java 账目组件生成 88 个新场景的完整 80-tick 状态集合，再启动原版。包括新增 BatBox→LV 升压、CESU→MV 降压、MFSU→HV/EV 降压，11 个此前未测的剩余空间值，以及零/满初始缓冲。预测未读取原版结果，共 7,280 个候选状态，每个时刻最多两个；预测 SHA-256 `e7cea7a2cbfb1b2867dec25eae3839b3035e1a371ee1066b57adff1455d5646d`。

`si-ic2-transformer-forward-r22-monarch-01-20260913` 的 7,040 笔状态全部在冻结预测内。再用同一归档 JAR 检验观察轨迹的每一步均可从上一状态转移：18 笔只符合输入先行，27 笔只符合输出先行，6,995 笔两者相同，没有不连贯转移。这里没有固定或预测选择概率；使用 r22 运行包装器身份不改变所验证 Java 组件为 R23 的事实。

原始数据 SHA-256 `9c8417c87ae95401a07225e6034bbe944d32c7d6e36789e097b5fe024f11bb04`，见 `evidence/r23/forward-prediction-freeze.json`、`forward-assessment.json`、`forward-java-check-01.log` 和 `forward-proof.json`。运行正常停止、存档、退出并核对收件哈希；远端约 5.129 GiB、Monarch 约 33.598 GiB，沿已授权本地隔离回退。未重建或更换组件，尚无 SI 集成验收。


## 模式切换的初始观察

`si-ic2-transformer-modes-r22-monarch-01-20260913` 完成 8 个场景/1,280 笔状态，覆盖 BatBox→LV、CESU→MV、MFE→HV 升压和 MFSU→EV 降压，分别从零/满缓冲开始。tick 40 切换物理红石、tick 80 切回；1,280 项红石方块状态检查及能量守恒检查通过。

保存的 active 标志在 tick 41/81 改变，但账目并不同步立即恢复：本组各路径 tick 41～82 停止收发，缓冲保留，tick 83 恢复。该次恢复的源端合计扣电为两个原输入电包；升压接收器获得一个对应输出单位，EV 降压接收器收到 4,096 EU。零/满初始缓冲的部分输出相位不同，不能只按显示 active 标志计算能量。

结果与恢复时发生多轮账目计算相容，但尚未验证不同切换时刻、短暂脉冲或其他拓扑，不能宣布一般补算规则。R23 组件应视为单次账目轮次；如何安排暂停、恢复及同世界 tick 的调用次数属于尚待验收的调度层，不把本组直接计为组件动态模式支持。

本组正常停止、存档、退出，收件哈希有效；原始记录 SHA-256 `b1b03fedb8b06b55cd2007979d30fb5d7a23c5ae58577773fa349c1e5eb5b844`，详见 `evidence/r23/modes-assessment.json`。没有修改归档核心或解除完整模组来源门禁。下一步以新切换时刻检验暂停/恢复轮次，再推进多面与 SI 接入。


## 新切换时刻的冻结预测验证

在原版启动前冻结输入、Java 预测器、归档核心和完整状态集合共 11 个文件；物理模式切换改为 tick 37/73。预测假设为 tick 38～75 零轮、tick 76 两轮、其他时刻一轮，每轮仍保留显式收发顺序。预测 SHA-256 `8ec5abe3eeb884965b09912cabb1247a740ab8e0dcf308392b6ce8f7c24aec59`。

`si-ic2-transformer-mode-holdout-r22-monarch-01-20260913` 的 8 个场景/1,280 笔状态全部属于预先冻结集合，1,280 项物理红石及守恒检查通过。使用未改动的归档 R23 Java 组件核对相邻实际状态：304 笔零轮、968 笔单轮、8 笔双轮，无法解释的转移为 0。原始记录 SHA-256 `f9abcbe41173f0d6947ad9d5bd060a8f5f3730070aaeb7749fdd8d40d44c8ba4`，详见 `evidence/r23/mode-holdout-prediction-freeze.json`、`mode-holdout-assessment.json`、`mode-holdout-java-check.log` 和 `mode-holdout-proof.json`。

远端实测约 5.1 GiB，低于 6 GiB 门槛，沿已授权 Monarch 隔离回退、单 JVM/2 GiB 堆完成。运行正常停止、存档、退出，收件哈希核验通过；登记世界及已核对保留副本的 incoming 传输包已清理。没有改变核心实现或完整来源门禁。该结果支持已测布局的新切换时刻，不证明短脉冲、多面、多变压器或一般调度概率。下一步推进批次跨接收器与 SI 接入所需的明确收发边界；阶段 2 仍未退出。


## 两输出面的独立批次分配

新增 32 个原版黑盒场景/1,280 笔账目：空电源、满缓冲的 LV/MV/HV/EV 降压变压器，通过顶部及侧面连接两个 MFSU，覆盖两端空仓及一个输出单位附近的剩余空间。所有账目守恒；原始记录 SHA-256 `5e7b99cf3ecddb2764f597bf90696e52abe0ece2cf0ef3ddbdf9347819722a17`，见 `evidence/r23/multi-observations.json`。物理放置、初始身份/余额及第二接收器均有检查。运行正常停止、存档、退出，证据核验后清理登记世界及 incoming 副本。

独立新增 `TransformerBatch`，引用纯 Java 的自有配置类型，按明确提供的接收顺序处理批次预算。LV 观测中剩余空间 33 EU 可接收整批 128 EU；剩余 31 EU 可精确接收 31 EU，剩余 97 EU 继续交给另一端。实现保留该批次行为，输入数组不变，不把剩余需求简单截断为整个批次的总上限。

1,280 笔实际相邻状态全部符合两个指定顺序之一：19 笔只符合 A→B，16 笔只符合 B→A，1,245 笔两者均符合。这是已知观测回顾验证，不是顺序预测；新时刻/更多接收端的前向验证仍待完成。新增契约 8,087 项，完整核心 17 组/1,578,882 项检查通过，外部和旧实现依赖均为 0。构建命令 `gradlew.bat --offline --no-daemon --max-workers=2 --console=plain :cleanroom-energy:check :cleanroom-energy:jar` 退出 0，日志 `evidence/r23/batch-core-check-build.log`。

新独立 JAR `0.14.1-r23-experimental` 为 54,082 字节，SHA-256 `29647132e1e2686ea09cc99888addeb9a6967cbf58227728df9353c08f6fd59e`；旧冻结 0.14.0 不变。尚未接入 SI，损耗、过压、多个变压器、实际顺序与游戏内性能不在本组件验证内。归档重建本次尚未运行，完整模组来源门禁不变。下一步先用新布局前向验证批次余量，再接入统一收发规划。


## 三输出面与非整包缓冲的前向验证

冻结 `0.14.1-r23-experimental` 归档 JAR 后，新增 LV/MV/HV/EV 共 48 场景，分别使用三个物理输出面、六组接收空间以及 3.5/8 个输出单位的初始缓冲。输入、预测 Java、组件 JAR 与夹具共 11 个文件先冻结，再启动原版；48 组完整 40-tick 预测共 5,760 个状态，每个时刻最多 5 个，覆盖调用方显式指定的六种接收顺序，不预测其概率。

`si-ic2-transformer-triple-r22-monarch-01-20260913` 的 1,920 笔状态全部符合冻结预测，源端保持 0，1,920 项守恒检查通过，第二/第三接收端的实际身份及初始余额均核对。归档 Java 再检查实际相邻状态，1,920 笔均可连贯转换。预测 SHA-256 `65f7bd90bd10e7097835b8bcfd30bc47252ae51cab9f0a6137f8bb3a64f4e89c`；原始记录 SHA-256 `b758ef2b1416822c9ec54f1b46247cbc5b828be3d584c1773ed236a62e090023`。见 `evidence/r23/triple-prediction-freeze.json`、`triple-assessment.json`、`triple-java-check.log`、`triple-proof.json`。

本轮未修改组件，归档核心仍为 `18670923bacae6ffef3ab6b512518630dc67aaa4`，正常停止/存档/退出及收件哈希通过；登记世界与 incoming 副本已清理。实测 Vicerach 约 4.1 GiB，低于门槛，继续使用已授权 Monarch 隔离回退。

### SI 接入前的具体边界

当前自有 `IndependentSiEnergy` 已按储能对象合并源扣电与接收加电，统一验证报价后提交；该机制可以保留。现有 `DomainDistributor.Source` 仍只提供一个包的预算，不能通过将 nominal packet 直接放大四倍来表示变压器批次：这会丢失已测部分需求边界，也会让使用 delivery.sourceDebit 的现有损坏规则把批次总量误认为单包电压。

接入需明确区分输出包大小、每轮包数和实际交付总额；同时把同一变压器源端已扣出的容量释放给后续接收阶段，而不允许本轮新收入反过来增加已经报价的输出预算。多轮恢复仍属于调度层。当前三输出面仅为无导线损耗、空上游电源的降压场景，不能替代损耗/过压或收发联动验证。尚未增加 SI 受控类型或更换任何冻结运行覆盖类；阶段 2 仍未退出。
