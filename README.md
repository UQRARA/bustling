# 熙熙攘攘（Bustling）

![Bustling](docs/logo.png)

熙熙攘攘为 Minecraft 添加装饰性人流。你可以在街道、车站和广场绘制路径、划定多边形区域，或放置支持红石控制的扩散装置。

支持 Minecraft Java 版 **1.21.1** 和 **NeoForge 21.1.x**。联机时由服务端保存并同步布局，客户端模拟和渲染人流。

Bustling adds decorative crowds to Minecraft. Draw paths, define polygonal areas, or place redstone-controlled Diffusion Devices in your builds. [English project description](docs/modrinth-en.md)

## 特色功能

- **三种人流布局**。沿开放路径或闭合环带布置人流，在多边形区域内散布行人，或为扩散装置设置半径、人数上限和外围衰减。
- **可配置的外观**。使用白模，或在对象编辑器中组合不同实体和皮肤并调整权重。内置中世纪城市居民和现代城市居民两套皮肤包。
- **复用人流配置**。复制工具可在条带、区域和扩散装置之间转移共通设置，包括翻越高度、人数密度、地面筛选、模型类型和权重。
- **客户端控制**。调整本机人数上限、站立比例、活跃半径和 CPU 预算，也可以打开方块脚步声。环境设置会根据昼夜和雨雪平滑调整目标人数。人流补员和退场会等到镜头视野外。

## 使用

- 将模组 JAR 放入 `mods`。在创造模式物品栏的「熙熙攘攘」分页获取工具。
- 手持区域编辑器，按住 Shift 并滚动滚轮切换模式。在区域或路径模式下，左键放置节点，右键完成。
- 用对象编辑器对准人流右键。切换至实体模式后，可在皮肤包列表中选用内置示例。
- 放置扩散装置后，空手右键方块可打开设置。使用复制工具时，对准人流布局或扩散装置，左键复制，右键粘贴。
- 本地皮肤包目录为 `config/bustling/skin-packs/`。

[下载 0.1.0-beta.18](https://files.catbox.moe/clv36s.zip)

[下载 0.1.0-beta.11](https://files.catbox.moe/n4fxwm.zip)

### 可选兼容 · Yes Steve Model

配合 Minecraft 1.21.1 NeoForge 版的 Yes Steve Model 2.6.5，在对象编辑器的「实体模式 → 皮肤包」首行打开「YSM 皮肤包」。单击选择文件夹后点「添加选中」可整组添加，双击文件夹可浏览并单选模型。列表读取当前世界的 YSM 可用模型，沿用 YSM 的同步和授权设置。

- 直接使用 YSM 的 `config/yes_steve_model/custom/`。模型文件夹可以保持解包状态，例如 `custom/example/ysm.json`。也可以使用 YSM 原生模型分组。
- 单人世界在皮肤包面板点击「YSM 文件夹」打开本地目录，放好模型后点击「重载本地」。服务器使用服务端的同名目录，安装 YSM 的客户端会随 YSM 同步收到可用模型。服务端新增模型后可执行 `/ysm model reload` 或重启。
- 也可把 `.ysm` 放入 `config/bustling/skin-packs/`，或把模型文件夹和压缩包放入其 `ysm/` 子目录。这个目录在加载世界时导入。

客户端设置中的「玩家互动」接受 `-30` 到 `30`。正值为避让。负值的绝对值为靠拢半径，人流面向近处玩家，在约两格外停住；玩家继续靠近时会面向玩家后退。0 关闭距离互动。互动仍受活跃范围、区域边界和地形限制。

客户端设置的「CPU 预算」可在 1–4 倍间调整碰撞查询和活跃人流初始化上限，大活跃范围下可按本机性能提高。可打开「脚步声」播放附近人流踩踏方块的声音。

## 工程结构

- `src/` 提供维护中的 Java 源码。
- `resources/` 包含 NeoForge 元数据、模型、纹理、翻译和内置皮肤包。
- `Blockbench/` 包含可编辑的工具模型及导出索引。
- `tools/` 和 `tests/` 包含构建工具与回归检查。

## 构建

本仓库以 **Bustling 0.1.0-beta.4** JAR 为基础，编译当前源码和字节码补丁，再将资源写入新 JAR。基础 JAR 提供历史版本中尚需依赖的已编译类和资源。发布版本号取自 `gradle.properties`。

需要 Java 21、Python 3、NeoForge 21.1.x 客户端的 `libraries` 目录，以及 `ecj.jar`、`asm.jar` 和 `asm-tree.jar`。

```sh
python3 tools/compile_skin_packs.py /path/to/bustling-0.1.0-beta.4.jar /path/to/libraries /path/to/build-tools
python3 tools/package_release.py /path/to/bustling-0.1.0-beta.4.jar
```

产物写入 `build/`。[获取基础版本 ZIP](https://files.catbox.moe/ylx3eg.zip)

检查 Blockbench 资源可运行 `python3 tools/export_bb_models.py validate`。
