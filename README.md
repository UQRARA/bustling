# 熙熙攘攘 · Bustling

![Bustling](resources/assets/bustling/icon.png)

为街道、车站和广场添加装饰性人流。支持 **Minecraft Java 版 1.21.1 · NeoForge 21.1.x**。

[下载当前版本](https://github.com/UQRARA/bustling/releases/tag/v0.1.0-beta.19) · [English](docs/modrinth-en.md) · [构建指南](docs/build.md)

## 使用

1. 将 JAR 放进 `mods`。工具位于创造模式的「熙熙攘攘」分页。
2. 手持**区域编辑器**，按住 Shift 滚动切换模式。左键放置节点，右键完成。路径放两个点形成条带，三个及以上可闭合成环。
3. 用**对象编辑器**设置白模、实体和皮肤包。支持原版实体、内置皮肤包和可选的 Yes Steve Model 2.6.5。
4. 空手右键**扩散装置**，可设置范围、密度、衰减和红石控制。**复制工具**可在不同布局间复制共通设置。
5. **客户端设置**可调整人数上限、站立比例、活跃半径、CPU 预算、互动、阴影和脚步声。互动值为正时避让，为负时聚集，为零时关闭。

本地皮肤包位于 `config/bustling/skin-packs/`。YSM 模型可放入 `config/yes_steve_model/custom/`，然后在对象编辑器的皮肤包列表里打开「YSM 皮肤包」。[皮肤包示例](examples/README.txt)

## 联机

服务端保存和同步布局，各客户端自行模拟和渲染人流。联机编辑时，客户端与服务端应使用同一发行版。未安装模组的客户端可加入服务器，扩散装置显示为刷怪笼。

本版把服务端数据写入对应世界的 `data/bustling.json`。首次迁移会保留原文件。
