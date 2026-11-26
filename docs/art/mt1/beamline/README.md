# 分子重组仪 · 横向束线试作

[模型与物品栏对照图](../../previews/MT1-beamline.png) · [四视图与电动压缩机同尺度对照](../../previews/MT1-beamline-views.png) · [游戏内物品栏截图](../../previews/MT1-beamline-client.png)

左侧发射头和右侧接收头对准玻璃窗里的工位。两端刻线在同一高度，工作时朝向样品的一小段亮起。主图中的铁锭用于演示装料位置。物品栏直接使用同一台机器的待机方块模型。

每态有 14 个元素、63 个面，内置三向电极版为 23 个元素、94 个面。玻璃罩占 3 个半透明面，工作态只有两端的 4 个刻线面使用满亮度。

已在 Minecraft 1.21.1、NeoForge 21.1.248 和奇点迭代 0.1.11.203 的同一客户端依次启用两种配色，实拍了背包中的待机模型。游戏从各自资源包加载待机和工作方块，每态均返回 63 个烘焙面。

### 样品对位

供客户端接入输入物品时参考。以 0–16 的方块模型坐标计，发射狭缝中心为 `(11, 9, 8)`，接收狭缝中心为 `(4.5, 9, 8)`。样品中心取 `(8, 9, 8)`，朝向与方块一起旋转。内腔为物品留出的空间从 `(5.5, 6.5, 5.5)` 到 `(10.5, 11.5, 10.5)`。两份资源包都交付空腔，运行时显示实际输入物品留给 Claude 接入。

## 在游戏里试用

1. 下载 [青蓝资源包](downloads/MT1-beamline-cyan.zip) 或 [紫粉资源包](downloads/MT1-beamline-violet.zip)。
2. 在已安装奇点迭代的 Minecraft 1.21.1 客户端打开「选项 → 资源包 → 打开资源包文件夹」，把 ZIP 原样放进去。
3. 启用一份配色并将它放在其他分子重组仪材质之上。放下机器，或在背包里查看物品。停用这份包即可恢复 [内置三向电极版](../README.md)。

## 美术文件

- [待机方块](pack/assets/mio_icif/models/block/producer/block_molecular_transformer.json)、[工作方块](pack/assets/mio_icif/models/block/producer/block_molecular_transformer_on.json)及[物品栏摆位](pack/assets/mio_icif/models/item/producer/block_molecular_transformer.json)沿用同一注册 ID，两个配色共用几何。
- 青蓝版 [待机](../../../../tools/models/mt1_beamline/molecular_transformer_beamline.bbmodel)和[工作](../../../../tools/models/mt1_beamline/molecular_transformer_beamline_on.bbmodel)的 Blockbench 工程内嵌贴图。
- 从仓库根目录依次运行 `python tools/art/mt1_beamline.py`、`python tools/art/mt1_beamline_pack.py` 和 `python tools/art/mt1_beamline_preview.py`，可重建模型与贴图、资源包及预览。紫粉版可编辑工程随打包生成在 `build/art/mt1/beamline/models/violet/`。
