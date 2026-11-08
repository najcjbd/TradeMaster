# 26.2 参考存档与配置

这是发布者调试 Trade Master 1.0.0 时使用的 **26.2 生存存档**（`新的世界 (1)`）与配置快照。它包含完整的装置、命令方块、水渠/铁轨和矿车补给示例。

## 安装

1. 把 `trade-master-26.2-rig-world.zip` 解压到 `.minecraft/saves/`（压缩包顶层就是 `新的世界 (1)/`，解压后即可在存档列表看到）；
2. 把本目录的 `trade-master.json` 放到实例的 **config 目录**（Fabric 通常是 `<游戏目录>/config/`；若启动器把游戏目录当作配置目录，则直接放在游戏目录）；
3. 使用 **26.2** 的 Trade Master jar 与对应版本的 **Fabric API**；
4. 进入世界。存档保存时讲台可能正处于被破坏状态，运行后模组会重新放置。

> 本示例配置为旧版 `configVersion: 1`，当前模组加载时会自动迁移；`positions.dispenser` 已由发布者补为工作发射器 `[17,-58,7]`（原始配置没有这一项）。

## 坐标（配置文件 `trade-master.json` 的 `positions`）

| 配置项 | 坐标 |
|---|---|
| lectern（讲台） | `14, -60, 8`（存档里为空气，运行后由模组补放） |
| summonButton（召唤按钮） | `17, -58, 8` |
| sendAwayButton（送走按钮） | `15, -60, 8` |
| dispenser（发射器） | `17, -58, 7`（示例配置补入；原始配置没有此项） |
| villagerRegionOrigin（暂存区原点） | `16, -58, 6` |
| villagerRegionDelta（暂存区延伸） | `0, 2, 0` |
| chests（箱子） | `14, -60, 11` |

配置中没有设置砂轮/命名相关位置。

## 矿车补给（本存档的实际做法）

- 工作发射器 `(17,-58,7)` 在召唤按钮旁；模板发射器 `(19,-59,8)` 内含 1 辆矿车。
- `(19,-60,8)` 的**循环命令方块**执行 `clone ~ ~1 ~ ~ ~1 ~ 17 -58 7 strict`，持续把模板克隆到工作发射器。
- `(20,-60,8)` 的**循环命令方块**执行
  `execute unless entity @e[x=17,y=-58,z=6,dx=1,dy=2,dz=-1,type=minecraft:villager] run summon minecraft:villager 19 -58 4`，
  在暂存区没有村民时召唤一个。
- 这是**“提供”方式**：工作发射器的矿车被召唤按钮发射后，克隆会把模板里的 1 辆补回；模板本身不会被消耗，因此**不需要回收矿车**。本存档的两个命令方块是循环命令方块（会持续执行）；如果你自建的是脉冲命令方块，请改成循环或加红石时钟，并且不要给模板发射器接按钮/红石。

## 配置摘要

- `configVersion: 1`（加载时迁移）；含旧键 `lockTarget: BOOK_TRADE`（当前版本不读取）。
- mechanism：`reroll=AXE`、`detection=PACKET`、`reserveCheaperTraders=false`、`minLockEmeralds=1`、`timeoutEnabled=false`、`timeoutTicks=1h`、`phaseTimeoutsEnabled=true`、`villagerCheckInterval=1s`、`lecternHealGrace=6s`、`professionHealCapEnabled=false`、`professionHealCap=3`、`autoDisableAfterLock=true`、`displayRolls=true`、`debugLog=true`。
- naming：`enabled=false`、`forceNamePriceOne=true`、`customNames={}`。
- antiCheat：`legitMode=true`、`slowMode=false`、`tpToVillager=true`、`preventAxeBreaking=false`、`stopOnPlayerMove=true`、`placeDelay=3`、`interactDelay=2`（没有 `tpToItem` 键，使用默认值）。
- hotkeys：`startStop=""`、`pickPosition=KP_3`。
- 启用的目标：节肢杀手 5、绑定诅咒 1、效率 5、摔落保护 4、火焰保护 4、穿刺 5、穿透 4、力量 5；其余附魔关闭，价格上限 64。

完整配置见本目录的 [`trade-master.json`](trade-master.json)，世界见 [`trade-master-26.2-rig-world.zip`](trade-master-26.2-rig-world.zip)。
