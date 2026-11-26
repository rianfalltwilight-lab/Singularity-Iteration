# 分子重组仪 · 四角约束腔

[模型与物品栏](../../previews/MT1-quadrupole.png) · [正、侧、顶、斜四视图](../../previews/MT1-quadrupole-views.png) · [游戏内背包实拍](../../previews/MT1-quadrupole-client.png)

按参考图把上下端各分为四个向内折的陶瓷夹角。俯看能从四道缺口辨认出独立方盖，侧看能透过烟色玻璃看到上下端面之间的工位。主图中的铁锭演示装料位置，物品栏直接绘制空腔待机方块。瓷夹顶面用瓷白，侧面用浅灰，使夹角在背包格里也能看出厚度。

## 试用

在装有奇点迭代的 Minecraft 1.21.1 客户端中，把[青蓝包](downloads/MT1-quadrupole-cyan.zip)或[紫粉包](downloads/MT1-quadrupole-violet.zip)原样放入 `resourcepacks`，在资源包菜单中启用，并置于其他分子重组仪资源包上方。停用后可切回[内置三向电极](../README.md)或已启用的[横向束线](../beamline/README.md)。

## 模型接入

方块模型和物品模型沿用已有注册名。动态装料留给 Claude 在客户端绘制。以北向方块的 0–16 模型坐标计，样品中心为 `(8, 8, 8)`，展示范围为 `(5.5, 5.5, 5.5)` 到 `(10.5, 10.5, 10.5)`。

| 部位 | 北向模型坐标与接入要点 |
| --- | --- |
| 上下四角 | 每个角件占 7×7，折臂宽 4。下层位于 Y=0–3，上层位于 Y=13–16，四边中点各有宽 2 的开口。 |
| 方盖与玻璃 | 两个闭合端盖与玻璃共用 X、Z=4–12 的截面。下盖止于 Y=3.5，上盖始于 Y=12.5，中间的四面烟色玻璃构成 8×9×8 的观察腔。 |
| 通电触点 | 下盖的 `core_glow` 朝上，上盖的 `upper_contacts_glow` 朝下。工作态只把这两个作用面及端盖北、西向的四个小指示标记提到满亮度。 |

待机和工作均为 **21 个元素、94 个面**。两端陶瓷夹角与端盖占 84 个实心面，灯纹占 6 个镂空面，玻璃占 4 个半透明面。

青蓝版的 Blockbench 工程内嵌贴图。[待机工程](../../../../tools/models/mt1_quadrupole/molecular_transformer_quadrupole.bbmodel) · [工作工程](../../../../tools/models/mt1_quadrupole/molecular_transformer_quadrupole_on.bbmodel)

从仓库根目录运行以下命令可重新生成全部模型、资源包和离线对照图。紫粉的两份可编辑工程随打包生成在 `build/art/mt1/quadrupole/models/violet/`。

```sh
python tools/art/mt1_quadrupole.py
python tools/art/mt1_pack.py quadrupole
python tools/art/mt1_quadrupole_preview.py
```
