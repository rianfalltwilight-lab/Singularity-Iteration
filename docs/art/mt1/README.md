# 分子重组仪 · MT1 交付

[打开青蓝与紫粉对照图](../previews/MT1.png)。上排各是一台工作中的重组仪和它的 16×16 图标，图标分别按 8 倍和原尺寸显示。下方四视图包含待机与工作两态，最底部的电动压缩机按相同比例渲染。

A 青蓝沿用原机的青绿和冰蓝，亮部为 `#2ADED3`、`#02D1F3`、`#3DBBE3`。B 紫粉用 `#B46BD8` 的紫面、`#D673EF` 的亮棱和 `#FFC7F0` 的粉色高光。两版共用白灰铸壳、石墨支架和三面观察窗。待机时透过玻璃能看到暗晶核，工作时晶核、背板上的两段场线和前面板上的三格灯亮起。图标保留了晶核、上下极靴和底座前灯。

## 文件

| 内容 | 位置 |
| --- | --- |
| 游戏模型 | [待机](../../../src/main/resources/assets/mio_icif/models/block/producer/block_molecular_transformer.json) · [工作](../../../src/main/resources/assets/mio_icif/models/block/producer/block_molecular_transformer_on.json) |
| A 青蓝 | [10 张部件贴图](../../../src/main/resources/assets/mio_icif/textures/block/producer/molecular_transformer/) · [物品栏图标](../../../src/main/resources/assets/mio_icif/textures/item/producer/item_molecular_transformer.png) |
| B 紫粉 | [10 张部件贴图](variant_b/block/producer/molecular_transformer/) · [物品栏图标](variant_b/item/producer/item_molecular_transformer.png) |
| Blockbench 工程，内嵌 A 贴图 | [待机](../../../tools/models/molecular_transformer.bbmodel) · [工作](../../../tools/models/molecular_transformer_on.bbmodel) |
| 生成脚本，位于 `tools/art/` | [模型与贴图](../../../tools/art/mt1_model.py) · [图标](../../../tools/art/mt1_icon.py) · [Blockbench 工程](../../../tools/art/mt1_blockbench.py) · [对照图](../../../tools/art/mt1_preview.py) |

B 的几何与 A 共用。要在 Blockbench 直接查看紫粉色，从仓库根目录执行 `python tools/art/mt1_blockbench.py --palette b`，然后打开 `build/art/mt1/variant_b/` 下生成的两个工程。

## 模型与发光

原机待机和工作各 1 个元素、6 个面。新机两态各 **24 个元素、112 个面**，共用同一组几何、UV 和旋转。实心层为 21 个元素、107 面，镂空层为 2 个元素、2 面，半透明玻璃为 1 个元素、3 面。所有可见面均为 1 单位对应 1 纹素。

| 元素 | 待机 | 工作 |
| --- | --- | --- |
| `core_glow` | 暗晶体 | 六面满亮 |
| `liner_glow` | 暗色场线 | 北面满亮，透明区域露出背板 |
| `status_glow` | 暗色三格灯 | 北面满亮，透明区域露出控制台 |

晶核以 `[8, 8, 7]` 为中心，边长 4，静态模型绕 Z 轴倾斜 45°。上下极靴向两侧错开，保留了晶核转动的空间。机壳与玻璃沿用环境光。俯视时检修盖遮住晶核，两版的区别在正面和斜视最明显。

## 给 Claude 的接入备注

模型坐标按 `(x, y, z)`，单位为 1/16 格，正面朝北（−Z）。下表可用于配置方块轮廓与碰撞。随 `facing` 绕方块中心旋转。观察窗与后衬是一体的封闭腔，内部零件可以合并到腔体碰撞中。

| 部位 | 范围 |
| --- | --- |
| 收进的底脚、满宽底座 | `[1,0,1]..[15,1,15]`，`[0,1,0]..[16,3,16]` |
| 玻璃腔、后衬 | `[3,3,2]..[13,13,12]`，`[3,3,12]..[13,13,14]` |
| 左右 C 形架 | X 分别为 `0..2`、`14..16`。上下横臂 Y 为 `3..5` 和 `11..13`，Z 均为 `5..13`。后脊 Y 为 `5..11`，Z 为 `10..13` |
| 北面控制台 | `[3,3,0.5]..[13,4,1.5]` |
| 顶部压盖、检修盖 | `[1,13,1]..[15,15,15]`，`[3,15,3]..[13,16,13]` |

- 当前分支已在 [方块的服务端 ticker](../../../src/main/java/com/miophas/singularity_iteration/common/block/producer/mio_icif_block_molecular_transformer.java#L114) 将 `isWorking()` 同步到 `lit`。[现有方块状态](../../../src/main/resources/assets/mio_icif/blockstates/producer/block_molecular_transformer.json) 已将四个朝向映射到两态模型。接入时用一条配方检查开始加工、断电和完成后的外观。
- [现有物品模型](../../../src/main/resources/assets/mio_icif/models/item/producer/block_molecular_transformer.json) 继承待机方块。物品栏要显示新图，可在 `neoforge:separate_transforms` 的 `perspectives.gui` 使用 `minecraft:item/generated`，`layer0` 指向 `mio_icif:item/producer/item_molecular_transformer`，其余视角沿用方块模型。
- 玻璃使用半透明层。设置遮挡时保留观察窗的透光，避免整格遮挡剔除相邻方块的可见面。旧的 96×16 横条留给接入时清理。

## 验证与待选

仓库检查器 `python3 tools/art/check_mt1.py` 在 macOS 输出 `OK`。已核对两套图标的透明边、两态发光面、UV，以及两个配色的 Blockbench 工程与游戏模型的对应关系。Gradle 离线构建通过，产物中的两个模型和 11 张正式贴图逐字节匹配。预览由实际模型和图标离线渲染。请在 **A 青蓝** 和 **B 紫粉** 中选定正式配色。
