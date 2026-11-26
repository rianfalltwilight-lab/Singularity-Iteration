# 分子重组仪 · 三向电极与中央工作筒

[青蓝与紫粉对照图](../previews/MT1.png)。主图的铁锭是客户端装料示意。物品栏格子使用各自的待机方块模型，按原版 GUI 角度、比例和侧光离线投影到 16 像素格，再作 8 倍最邻近放大。两套游戏资源都直接沿用方块物品模型。

外形参照 Minecraft 1.7.10 的 Advanced Solar Panels 3.5.1。原包实际绘制十个部件，中央工作筒上下各有三组相隔约 120° 的端头。三个方向都能看进工作区。旧模型还定义了七段式弓形支臂，实际绘制时跳过了这些中段。本版采用窄筒、分开的端头和露空机腰，用奇点迭代的白灰金属与两种工作色重新绘制。[作者发布的 3.5.1 安装包](https://forum.industrial-craft.net/core/attachment/4316-advancedsolarpanel-1-7-10-3-5-1-jar/) · [1.7.10 版本与文件校验值](https://mcarchive.net/mods/advancedsolarpanels?gvsn=1.7.10)

## 文件

| 内容 | 位置 |
| --- | --- |
| 游戏模型 | [待机](../../../src/main/resources/assets/mio_icif/models/block/producer/block_molecular_transformer.json) · [工作](../../../src/main/resources/assets/mio_icif/models/block/producer/block_molecular_transformer_on.json) |
| A 青蓝 | [部件贴图](../../../src/main/resources/assets/mio_icif/textures/block/producer/molecular_transformer/) |
| B 紫粉 | [部件贴图](variant_b/block/producer/molecular_transformer/) |
| 物品栏模型 | [直接继承待机方块](../../../src/main/resources/assets/mio_icif/models/item/producer/block_molecular_transformer.json) |
| Blockbench 工程，内嵌 A 贴图 | [待机](../../../tools/models/molecular_transformer.bbmodel) · [工作](../../../tools/models/molecular_transformer_on.bbmodel) |
| 生成脚本，位于 `tools/art/` | [模型与贴图](../../../tools/art/mt1_model.py) · [Blockbench 工程](../../../tools/art/mt1_blockbench.py) · [对照图](../../../tools/art/mt1_preview.py) |

B 的几何与 A 共用。要在 Blockbench 直接查看紫粉色，从仓库根目录执行 `python tools/art/mt1_blockbench.py --palette b`，然后打开 `build/art/mt1/variant_b/` 下生成的两个工程。

## 结构与用面

方块正面朝北（−Z）。前一版在机腰高度占满 16×16，这一版同一截面只有 **6×6** 的透明工作筒。较宽的端头分别收在上下两端，从侧面也能看到样品。顶部三组电极以一后、两侧偏前的方式布置。两侧端头用原生 Java 方块模型支持的 ±22.5° 转角实现。

待机和工作都是 **23 个元素、94 个面**。实心层 13 个元素、73 面，镂空灯纹 9 个元素、17 面，半透明工作筒 1 个元素、4 面。两态几何及 UV 一致。工作时以下 17 个面提到满亮，其余表面随环境照明变化。

| 元素 | 工作态满亮面 |
| --- | --- |
| 后、左、右三组端帽及底脚上的 `*_glow`（6 件） | 各自顶面与外侧面，共 12 面 |
| `core_glow`，下反射头的灯纹 | 北面、上面 |
| `upper_reflector_glow`，上反射头的灯纹 | 北面、下面 |
| `status_glow`，下盘状态灯 | 北面 |

## 给 Claude 的接入备注

**显示输入物。** 样品中心移到 `[8,8,8]`，显示包围盒可收在 `[5.5,5.5,5.5]..[10.5,10.5,10.5]` 内。坐标单位为 1/16 格，随 `facing` 绕方块中心旋转。取输入槽的一件物品，空槽显示空腔。断电时按槽内实物显示。展示铁锭的主图使用预览脚本里的独立模型，随配方显示输入物由客户端接入。[输入槽](../../../src/main/java/com/miophas/singularity_iteration/common/blockentity/producer/mio_icif_molecular_transformer.java#L36) · [已有的物品绘制参考](../../../src/main/java/com/miophas/singularity_iteration/common/client/suit/QuantumModStationRenderer.java#L44)

**工作状态与物品栏。** 方块服务端已按 `isWorking()` 切换 `lit`。物品模型现有的 `parent` 直接指向待机方块。库存、热键栏和创造模式物品列表都沿用这个模型。复核图中 16 像素格的投影参数来自原版 1.21.1 的 `minecraft:block/block`，即旋转 `[30,225,0]`、缩放 `[0.625,0.625,0.625]`、侧光。游戏能在不同 GUI 缩放下重绘几何。[工作状态](../../../src/main/java/com/miophas/singularity_iteration/common/block/producer/mio_icif_block_molecular_transformer.java#L114) · [物品模型](../../../src/main/resources/assets/mio_icif/models/item/producer/block_molecular_transformer.json)

**碰撞与遮挡。** 模型各部件按下表布置。设置碰撞时可由这些位置合并出简化外形，机腰两侧和端头之间的开口需要保留。几何精确坐标、端头旋转原点均可从方块 JSON 读取。

| 部位 | 北向模型坐标 |
| --- | --- |
| 下盘 | `[3,1,3]..[13,3,13]`，上方由密封圈接到玻璃筒 |
| 工作筒 | `[5,4,5]..[11,12,11]`，四侧半透明。上下作用面分别在 Y=5、11 |
| 上盘及顶部接线端 | `[4,12,4]..[12,14,12]`、`[6.5,14,6.5]..[9.5,16,9.5]` |
| 三组底脚和端帽 | XZ 中心依次为后 `[8,13.25]`、左前 `[3,5]`、右前 `[13,5]`。底脚 Y 为 `0..4`，端帽 Y 为 `12.5..15.5` |

## 验证与待选

批次检查器现校验物品父模型，以及两版镜像的方块贴图。仓库检查与 Gradle 离线构建通过。已验证 Blockbench 的内嵌贴图、UV、旋转与发给游戏的模型相符。A 青蓝和 B 紫粉均保留供选。
