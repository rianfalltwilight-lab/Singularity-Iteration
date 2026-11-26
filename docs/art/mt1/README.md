# 分子重组仪 · 样品观察腔

[青蓝和紫粉的模型、图标与四视图](../previews/MT1.png)。两组主图以铁锭为例，预演客户端把输入物放进观察窗后的效果。四视图使用实际静态资源，可检查空腔和两态照明。图标在物品栏底色上分别放大 8 倍和按原尺寸显示。

分子重组核心的配方里，六块光辉玻璃板围住工作区，上下各有一块加厚中子反射板。整机再配两台超高压变压器。模型把厚机头放在样品上下，正面与侧面前段留出视窗。工艺光沿机头内缘亮起，中央的位置留给各配方的原料。青蓝与紫粉共用壳体、玻璃和铁锭示意，换色的是机器上的工作灯及图标对应的像素。[核心配方](../../../src/main/resources/data/mio_icif/recipe/asp_molecular_transformer_core.json) · [整机配方](../../../src/main/resources/data/mio_icif/recipe/shaped_molecular_transformer.json) · [铁锭示例配方](../../../src/main/resources/data/mio_icif/recipe/molecular_transformer/iron_ingot_to_iridium_ore.json)

## 文件

| 内容 | 位置 |
| --- | --- |
| 游戏模型 | [待机](../../../src/main/resources/assets/mio_icif/models/block/producer/block_molecular_transformer.json) · [工作](../../../src/main/resources/assets/mio_icif/models/block/producer/block_molecular_transformer_on.json) |
| A 青蓝 | [部件贴图](../../../src/main/resources/assets/mio_icif/textures/block/producer/molecular_transformer/) · [物品栏图标](../../../src/main/resources/assets/mio_icif/textures/item/producer/item_molecular_transformer.png) |
| B 紫粉 | [部件贴图](variant_b/block/producer/molecular_transformer/) · [物品栏图标](variant_b/item/producer/item_molecular_transformer.png) |
| Blockbench 工程，内嵌 A 贴图 | [待机](../../../tools/models/molecular_transformer.bbmodel) · [工作](../../../tools/models/molecular_transformer_on.bbmodel) |
| 生成脚本，位于 `tools/art/` | [模型与贴图](../../../tools/art/mt1_model.py) · [图标](../../../tools/art/mt1_icon.py) · [Blockbench 工程](../../../tools/art/mt1_blockbench.py) · [对照图](../../../tools/art/mt1_preview.py) |

B 的几何与 A 共用。要在 Blockbench 直接查看紫粉色，从仓库根目录执行 `python tools/art/mt1_blockbench.py --palette b`，然后打开 `build/art/mt1/variant_b/` 下生成的两个工程。

## 结构与用面

游戏模型正面朝北（−Z）。待机和工作均为 **24 个元素、88 个面**，比上一版少 24 个面。实心层 19 个元素、80 面，镂空的灯纹层 3 个元素、5 面，半透明玻璃层 2 个元素、3 面。两个状态使用同一套几何和 UV，贴图保持 1 单位对应 1 纹素。

下反射头的工作面在 Y=5，上反射头在 Y=11。二者之间有 6 个单位净空。前窗宽 12 单位，侧窗延伸至机身中段，侧后方由实体壳承托。待机时可透过玻璃看见暗色电极。工作时只有下表所列的面显示满亮，透过镂空灯纹露出的金属与玻璃继续随环境光明暗变化。

| 灯纹元素 | 工作态满亮面 |
| --- | --- |
| `core_glow`，下反射头的电极内缘 | 北面、上面 |
| `upper_reflector_glow`，上反射头的电极内缘 | 北面、下面 |
| `status_glow`，前面板状态灯 | 北面 |

`core_glow` 现为下反射头的一部分。动态样品有独立的放置坐标。对照主图中的银色铁锭由预览脚本单独绘制，方块模型、Blockbench 工程和物品图标均可用于任意配方。

## 给 Claude 的接入备注

**样品显示。** 样品中心为 `[8,8,6]`，建议将显示包围盒收在 `[5,6,3.5]..[11,10,8.5]` 内。坐标以 1/16 格为单位，随 `facing` 绕方块中心旋转。显示内容取机器输入槽 `INPUT_SLOT` 的一件样品。空槽时留空，断电或输出堵塞时按槽中实际物品显示。注意将样品变化同步给正在观察机器的客户端，仓库里的量子改装台已有物品同步和绘制参考。[输入槽](../../../src/main/java/com/miophas/singularity_iteration/common/blockentity/producer/mio_icif_molecular_transformer.java#L36) · [物品同步参考](../../../src/main/java/com/miophas/singularity_iteration/common/suit/QuantumModStationBlockEntity.java#L385) · [物品绘制参考](../../../src/main/java/com/miophas/singularity_iteration/common/client/suit/QuantumModStationRenderer.java#L44)

**灯光与图标。** 当前服务端已经按 `isWorking()` 切换 `lit`，四个朝向均有两态映射。审核新物品栏图标时，在现有物品模型的 GUI 视角接入 `mio_icif:item/producer/item_molecular_transformer` 即可。手持和掉落物可沿用方块模型。四类工艺的差异继续由 GUI 胶片呈现。[工作状态](../../../src/main/java/com/miophas/singularity_iteration/common/block/producer/mio_icif_block_molecular_transformer.java#L114) · [方块状态](../../../src/main/resources/assets/mio_icif/blockstates/producer/block_molecular_transformer.json) · [物品模型](../../../src/main/resources/assets/mio_icif/models/item/producer/block_molecular_transformer.json)

**碰撞与遮挡。** 机身主要外形如下。碰撞可以覆盖封闭腔体，计算遮挡时应保留透过玻璃可见的相邻方块表面。

| 部位 | 北向模型的范围（X、Y、Z） |
| --- | --- |
| 底脚与底座 | `[1,0,1]..[15,1,15]`，`[0,1,0]..[16,3,16]` |
| 上壳与检修盖 | `[0,13,0]..[16,15,16]`，`[1,15,1]..[15,16,15]` |
| 侧后壳与腔后壁 | 左右后壳 X 为 `0..3` 和 `13..16`，Z 为 `8..16`。后壁 X 为 `3..13`，Z 为 `9..16`。三者 Y 均为 `3..13` |
| 观察窗 | 前玻璃 `[2,4,1]..[14,12,1]`。两侧玻璃在 X=1、15，从 Z=1 延伸至 Z=8，Y 为 `4..12` |

## 验证与待选

仓库原检查器 `python3 tools/art/check_mt1.py` 通过。已核对两态几何、UV、满亮面及两版嵌入 Blockbench 工程的贴图。Gradle 离线构建通过。对照图来自离线渲染，游戏内显示实际输入物和专用物品栏图标由 Claude 接入。A 青蓝和 B 紫粉均保留供选。
