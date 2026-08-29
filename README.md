# LightningCrowbar

一个兼容 Luminol/Folia 的 Paper 插件：使用带有特殊标记的铁镐作为撬棍。手持撬棍攻击生物时，会在目标位置召唤一道闪电，保留普通攻击伤害并叠加闪电伤害。

## 构建

需要 Java 25 和 Gradle 9 或更高版本：

```powershell
gradle build
```

构建产物位于 `build/libs/LightningCrowbar-1.0.0.jar`。将它放入 Luminol 服务端的 `plugins` 目录后启动服务器。

当前 Luminol 26.2 源码归档没有可下载的服务端发行包，因此本仓库的本地测试基线使用同 API 线的 Folia 26.2 build 7；Luminol 服务端可直接替换测试服 JAR。

## 游戏内测试

玩家可以直接执行：

```text
/lightningcrowbar give
```

也可以指定在线玩家：

```text
/lightningcrowbar give <玩家名>
```

手持撬棍攻击任意生物即可触发闪电。撬棍也可以用两个铁锭和两根木棍按配方合成。
