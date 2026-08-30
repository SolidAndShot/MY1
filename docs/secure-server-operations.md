# 小型安全服务器运维说明

## 当前安全配置

- 最大人数：12 人。
- 服务端口：TCP `25565`。
- 正版身份验证：已启用 `online-mode`。
- 安全聊天档案：已启用 `enforce-secure-profile`。
- 白名单：已启用，当前只有 `Vanillay`。
- 管理员：`Vanillay`，权限等级 4。
- RCON、Query、JMX 和 Management Server：全部关闭。
- 在线玩家名称不会通过服务器状态查询公开。
- 出生点保护范围：16 格。
- 无人在线 60 秒后暂停世界刻，减少资源消耗。
- 玩家空闲 30 分钟后自动断开。
- 最大世界边界：10000 格，降低恶意探索造成的磁盘占用。
- 视距 6、模拟距离 4，适合小型服务器。
- 公网连接使用 Playit 单端口出站隧道，不需要在路由器上公开电脑的其他端口。

## 启动与停止

首次 Playit 绑定完成后，双击 `test-server/start-secure-server.cmd`。脚本会在后台启动隧道，并在可见窗口中启动 Minecraft 服务端。

安全停止服务器时，在服务端窗口输入：

```text
stop
```

服务端保存世界并退出后，启动脚本会自动停止它启动的 Playit 进程。

不要直接关闭窗口或结束 Java 进程，否则可能造成未保存的区块和玩家数据丢失。

## 白名单管理

只有白名单内的正版账号可以连接。请在服务端控制台使用：

```text
whitelist add 玩家名
whitelist remove 玩家名
whitelist list
```

不要为了临时测试关闭白名单。新玩家加入时逐个添加，离开长期成员组后及时移除。

## 管理员管理

只给可信任的维护者管理员权限：

```text
op 玩家名
deop 玩家名
```

普通参与者不需要管理员权限。躲猫猫游戏只需要一名管理员通过 `/hns admin` 控制。

## 日常检查

- 开服后确认控制台出现 `Done` 和 `LightningCrowbar enabled on Luminol/Folia`。
- 只将 Playit 提供的 Minecraft 地址分享给白名单成员。
- 定期备份 `test-server/world`、`world_nether`、`world_the_end`、`ops.json` 和 `whitelist.json`。
- 更新服务端或插件前先输入 `stop`，不要热替换正在加载的 JAR。
- 不要启用 RCON、Query 或 Management Server，除非同时配置独立强密码和受限防火墙规则。

