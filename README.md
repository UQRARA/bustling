# 熙熙攘攘（Bustling）

![Bustling](docs/logo.png)

熙熙攘攘为 Minecraft 添加装饰性人流。你可以绘制路径和多边形区域，让行人在街道、车站和广场移动，也可以放置支持红石控制的扩散装置。人物支持白模、实体模型和自定义皮肤包，内置中世纪和现代城市居民两套示例。

支持 Minecraft Java 版 **1.21.1** 和 **NeoForge 21.1.x**。联机时由服务端保存并同步布局，客户端模拟和渲染人流。

Bustling adds decorative crowds to Minecraft. Design paths and polygonal areas, place redstone-controlled diffusion devices, or customize crowd appearances using the bundled and editable skin packs.

## 使用

- 将模组 JAR 放入 `mods`。在创造模式物品栏的「熙熙攘攘」分页获取工具。
- 手持区域编辑器，按住 Shift 并滚动滚轮切换模式。在区域或路径模式下，左键放置节点，右键完成。
- 用对象编辑器对准人流右键。切换至实体模式后，可在皮肤包列表中选用内置示例。
- 本地皮肤包目录为 `config/bustling/skin-packs/`。

[下载 0.1.0-beta.11](https://files.catbox.moe/n4fxwm.zip)

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
