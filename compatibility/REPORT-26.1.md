# 精确 Minecraft 26.1 支持与验证

日期：2026-11-16。项目版本：`0.2.0-alpha.1`。这次新增 **26.1**，不是把 26.1.2 的版本范围改宽，也不替换其他 11 个目标的已测 JAR。默认构建目标仍为 26.2。

## 基线与实现

| 组件 | 精确基线 |
| --- | --- |
| Minecraft | 26.1 |
| 服务器 / 构建 Java | 25 |
| Fabric Loader / Installer | 0.20.1 / 1.1.2 |
| Fabric API | 0.145.1+26.1 |
| MinecraftPlatform | permissions-nanos |
| Loom / Gradle | 1.19.4 / 9.8.2 |
| 宿主 | fabric-hotload-host-0.2.0-alpha.1+mc26.1.jar |

在唯一配置源 `targets.json` 添加独立目标，复用已存在的权限/tick 纳秒适配层，针对 **26.1 本身**编译未混淆宿主与普通探针。宿主描述符保持 `minecraft: =26.1`，没有修改运行时代码。Fabric API 与 26.1.2 的基线不同。新增 Python 回归检查避免误用其他版本的基线。

## 实际结果

| 环境 | 构建 / 单测 | 服务器检查 | 受管生命周期循环 | 证据 |
| --- | --- | --- | --- | --- |
| Windows x64 / Oracle Java 25.0.4.1 | 构建、分发校验成功；13 项 JVM 单测通过 | 13 / 13 | 100 次，PID/session 不变 | [矩阵](../verification/matrix-20261116T041505330275Z.json)、[服务器](../verification/integration-20261116T041639480685Z-mc26.1.json) |
| Linux x64 / MCSManager Docker daemon / Microsoft Java 25.0.4.1 | 使用与 Windows 实测完全相同的 6 个 JAR，逐项核对 SHA256 | 13 / 13 | 100 次，PID/session 不变 | [服务器](../verification/integration-20261116-linux-mcsm-mc26.1.json) |

此外，本次 Python 分发边界测试 **8 项**、发布/报告工具测试 **10 项**通过。Linux 行不是声称在该机器重新编译。CI 在 Linux/Windows 对 12 个目标构建和运行单元/边界测试，**不启动 Minecraft 服务器**；CI 结果应查看仓库 Actions。

两种服务器环境均先观察普通探针正常安装后的行为，再验证运行时加载后的 Loader 容器、main/server 入口点、JAR 资源和持续 tick；重复 ID 被拒绝。随后验证受管模块 v1→v2、停用后命令拒绝，以及 100 次 load/reload/disable 循环后 `resources=0 dirty=false modules=[]`。

Agent 探针验证仅限本项目自己的测试类：方法体补丁保留启动 Mixin，新增字段被检查器和 JVM 拒绝，恢复原方法体。最后验证正常保存/停止、同一世界重启，以及故意初始化失败的普通 Mod 标记脏状态并阻止继续激活。

这些保存/恢复测试有正常重启，**不是**热加载循环中的重启。Linux 负向测试后再次正常停止并干净启动，最终状态为 `resources=0 dirty=false modules=[]`，MCSManager 状态 3（运行中）。首次从原版安装 Fabric 同样需要正常停止/启动，不能热安装宿主。

## 产物与哈希

Windows 与 Linux 都测试以下相同宿主：

```text
fabric-hotload-host-0.2.0-alpha.1+mc26.1.jar
SHA256 6aa4421008b52c5317a688d7c7dbd7ee74690ef33a98da743efc064c6054303e
```

其余 5 个 JAR 的 SHA256 在两份服务器报告及 `distribution.json` 中记录。打包前重新核对全部目标的分发哈希；[results-20261116.json](results-20261116.json) 合并原 11 个 Windows 目标的历史成功记录与本次 26.1 Windows 记录，**不是把旧目标重测，也不是 12 个目标都在 Linux 上通过**。原始报告里的未验证列表保留该次运行时的范围，不改写历史。

Release `v0.2.0-alpha.1-mc26.1` 使用新标签，不移动旧标签。完整包包含 12 个精确目标的 72 个已测 JAR、各自分发清单、源码与公开验证记录，不分发 Minecraft 服务端、世界、下载依赖或原始日志。

## MCSManager 部署与恢复

仅修改用户授权的新建独立测试实例，先正常停止并备份原版 `server.jar`、`server.properties`、`eula.txt`、实例配置和世界，备份目录为该实例内的 `vanilla-baseline-before-fabric-20261116/`。原版服务端 SHA256：

```text
98ab064389a8b34d48ac3d4c5ed858dd9433d0dfd1a12e1cbbda916448d92994
```

本实例安装上述 Loader/API/宿主/Agent，使用 2 GiB 堆和 4 个逻辑 CPU，保留正版验证；未开启自动启动/自动重启。没有重启 MCSManager 守护容器、修改其他实例或替换旧容器端口。仅用户明确同意的这个新实例写入 EULA 接受记录。

回退时只操作这个测试实例：

1. 在面板正常保存并停止，另存当前世界、日志和本次 JAR。
2. 核对备份的 `SHA256.json`，将面板启动命令改回备份实例配置中的原版 `-jar server.jar` 命令，去掉 Agent 和实验热加载参数；原版 JAR 一直保留。
3. 要恢复到测试前世界时，在停止状态下移出当前世界，复制备份世界和配置；不能在线覆盖世界，也不要删除唯一备份。
4. 正常启动并检查日志。Fabric 文件可以另存；原版启动不读取 `mods/`。不要恢复整个守护容器或动其他实例。

这里只提供备份及人工恢复方法，**没有执行覆盖世界的回退测试**。源码修改前的额外快照和哈希保存在开发机 `history/mc26.1-start-20261116T041444Z/`，不随开源包发布；可在新目录检出旧标签回看，避免覆盖已有工作。

## 明确限制

- 尚未实际测试真实第三方 Mod、真实玩家登录/不断线、完整 JVM 类卸载。
- 普通 Mod 仍仅实验性新增，不提供通用卸载、更新或初始化副作用回滚。声明 Mixin、访问变换、嵌套 JAR 等候选仍拒绝。
- 未解决任意冻结注册表、客户端同步、启动事件补跑或任意资源/网络集成问题。测试探针成功不等于所有 Mod 都可以热加载。
- 其他 11 个目标的 Linux 服务器行为尚未实测。仅列出的精确版本有本项目的对应基线，不能据此推断相邻版本兼容。

构建入口：`./gradlew build distribution -Ptarget=26.1`。安装及命令说明见 [README](../README.md)。
