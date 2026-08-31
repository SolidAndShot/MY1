# Luminol Hide and Seek v1.0

v1.0 是本项目的首个正式发行版，面向 Minecraft 26.2 的 Luminol/Folia 服务端。

## 环境要求

- Minecraft 26.2
- Luminol 26.2 build 727，或兼容同 API 线的 Folia 下游
- Java 25

## 安装

1. 下载发行资源 `LightningCrowbar-1.0.jar`。
2. 将 JAR 放入服务端 `plugins` 目录。
3. 完整重启服务端。
4. 管理员执行 `/hns admin` 打开游戏管理控制台。

## 首局流程

1. 使用 `/hns givehat [玩家]` 发放参赛帽，参与者需要佩戴帽子。
2. 在 `/hns admin` 中设置角色、道具开关、体型和随机事件。
3. 点击“开始 10 分钟游戏”或执行 `/hns start`。
4. 对局中行动栏会显示剩余时间、下一个体型节点和躲藏者数量。
5. 所有躲藏者被淘汰时抓捕者胜利；时间结束仍有躲藏者存活时躲藏者胜利。

## v1.0 验证范围

- Java 25 与 Gradle 9 构建通过。
- Folia 26.2 本地实例完成插件加载验证。
- Luminol 26.2 build 727 云端实例完成插件加载验证。
- 游戏结算清理和三级特效哨已按 Folia 区域线程限制完成兼容处理。

完整功能与配置说明参见项目根目录的 `README.md`。
