Bustling 皮肤包示例 · Canal Market

安装
  将 canal_market 文件夹放进 config/bustling/skin-packs/。
  游戏中对人流使用对象编辑器，切换到「实体模式」，点「皮肤包」。
  点「重载本地」可重新读取目录，再选「运河市集」加入当前人流。
  实体列表中的百分比表示这个包占整组人流的比例。

联机
  服务端使用同一个目录。放入皮肤包后重启服务端。
  支持皮肤包的客户端在加入时接收服务端包。服务端包优先于同 ID 的本地包。
  本地包只在安装了该包的客户端可见。缺少皮肤包的客户端使用默认外观。
  人流外观选择随人流配置保存；服务端同步的是纹理与权重，客户端执行渲染。

新建包
  复制 canal_market，改成唯一的目录名。该名称就是包 ID，
  允许小写字母、数字、下划线和连字符，以字母或数字开头，最多 48 字符。
    <包 ID>/pack.json
    <包 ID>/textures/皮肤.png
  pack.json 使用 UTF-8。name 是显示名，description 是风格介绍。
  skins 中的 texture 对应 textures 内的文件名，例如 courier.png。
  使用 Minecraft 标准 64×64 PNG 皮肤，支持帽子、外套等第二层。
  model 为 wide（Steve 宽臂）或 slim（Alex 窄臂）。
  weight 为 1 到 10000 的相对权重。示例 5:3:2 表示长期约 50%:30%:20%；
  小规模人群的实际比例会随抽样变化。每包最多 32 张皮肤。

Install
  Copy canal_market into config/bustling/skin-packs/.
  Open the Object Editor on a crowd, enable Entity Models, then open Skin Packs.
  Reload local packs if needed, and select Canal Market.
  Dedicated servers load the same directory on startup and sync packs when modded players join.

Create a pack
  Copy the example, rename the folder, and edit pack.json. The folder name is its ID.
  Use 64×64 Minecraft PNG skins in textures/. Use wide for Steve arms or slim for Alex arms.
  Weights are relative within the pack. The percentage in the Object Editor controls the pack's
  share of the entire crowd. Server packs take priority over local packs with the same ID.
