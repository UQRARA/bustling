# 构建 Bustling

当前源码以 **Bustling 0.1.0-beta.4** 的基础 JAR 提供历史类和资源。覆盖编译使用 Java 21、Python 3、NeoForge 21.1.x 客户端的 `libraries` 目录，以及 `ecj.jar`、`asm.jar`、`asm-tree.jar`。回归检查还需要 `gson.jar`。

1. 从构建依赖 ZIP 解压基础 JAR，放到 `deps/`。该 JAR 的 SHA-256 为 `65cdc71988b4b0efb366c2f227e13dc39097a47d345c679689d32fc9ebd55bb3`。[构建依赖](https://files.catbox.moe/ylx3eg.zip)
2. 在仓库根目录运行下列命令，替换客户端依赖和构建工具的路径。

```sh
python3 tools/compile_skin_packs.py deps/bustling-0.1.0-beta.4.jar /path/to/libraries /path/to/build-tools
python3 tools/package_release.py deps/bustling-0.1.0-beta.4.jar
python3 tests/run.py classes build/bustling-0.1.0-beta.19.jar /path/to/build-tools
python3 tools/export_bb_models.py validate
```

发行版本号以 `gradle.properties` 为准。构建产物写入 `build/`。
