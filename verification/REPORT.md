# P0 验证报告

日期：2026-11-13（Asia/Shanghai）。结论：**技术探针成立，尚不是通用模组热重载工具。**

## 环境及最终版本

- Windows 11；Oracle JDK 25.0.4.1。
- Minecraft 26.2 / Fabric Loader 0.20.1 / Fabric API 0.161.0+26.2。
- Gradle Wrapper 9.8.2 / Fabric Loom 1.19.4 / Fabric Installer 1.1.2。
- 用户明确同意在本项目隔离实例中接受 Minecraft EULA；服务器绑定 127.0.0.1，没有实际玩家加入。
- 原始 Minecraft 与 Mod JAR 不修改；候选在额外目录按 SHA256 暂存。

主要机器可读结果：`integration-20261113T054723Z.json`，`success=true`。
真实 stdout、控制台输入/响应与进程退出状态保存在原始开发环境的 `raw/20261113T054723Z/`，不提交到公开仓库。公开 JSON 保留真实结果与 SHA256，仅把本机绝对路径规范化为相对路径；原始报告和哈希另行在本地备份。

## 实际命令

在本项目目录执行，`JAVA_HOME` 指向 Oracle JDK 25.0.4.1。下面是原始命令的公开规范化版本：将本机路径表达为环境变量，移除机器专用的代理端点；这不是新的运行记录。原始命令保存在本地备份与原始日志中。

```text
gradlew.bat build distribution --console=plain --warning-mode all
python -m py_compile integration-tests/run_probes.py
python integration-tests/run_probes.py --java-home "$env:JAVA_HOME" --minecraft-server "$env:MINECRAFT_SERVER_JAR" --cycles 100 --accept-eula
```

Gradle 使用本机显式 JVM 代理参数，未修改系统配置、未记录代理凭据。构建日志为 `raw/build-release.log`，退出状态 0；集成脚本退出状态 0。
服务器控制台命令不是独立 OS 进程，没有单独进程退出码；记录实际响应，另记录每次启动的 JVM 最终退出状态。

## 结果

| 检查 | 实际结果 |
| --- | --- |
| 单元测试 | 10 项：core 8、agent 2；0 失败、0 错误、0 跳过 |
| 正常启动的普通 Fabric 探针 | `main=1 server=true`，容器与资源读取成功，tick 回调实际运行 |
| 启动后加载同一探针 | 相同初始化与资源行为，tick 从较小值继续增长；不是只让模组列表显示存在 |
| 重复普通 Mod | 提交前拒绝重复 ID，不重复初始化 |
| 受管版本更新 | 实际返回从 `demo-v1` 变为 `demo-v2` |
| 停用 | 原命令不可再调用，受管资源归零 |
| 100 次服务器内循环 | 同一 PID 14416、同一 session；结束 `resources=0 dirty=false modules=[]` |
| 方法体替换 | `original → patched`；已有启动 Mixin 调用仍存在，`mixinDelta=2` |
| 新增字段负向测试 | schema guard 与 JVM 均拒绝；原有行为保持 |
| 字节码恢复 | `patched → original`；Mixin 调用保持 |
| 普通 Mod 初始化异常 | 标记 `DIRTY_RESTART_REQUIRED`，后续加载被阻断；不宣称回滚成功 |
| 保存及重启 | 正常保存、进程退出 0；另一次启动可打开同一个测试世界 |

100 次循环后的诊断快照：`avgTickMs=5.420092`、`maxTickGapMs=73.849`。前者由 Minecraft 的平均 tick 时间接口提供，后者为宿主 END_SERVER_TICK 回调间最大间隔，包含正常 tick 等待，**不是最大 MSPT**。这不是零停顿证明，也没有在线玩家体验测量。

最终集成运行包含正常安装、热加载、保存后重启、失败注入四次服务器启动，均正常停止并退出 0。

## 临时失败与修复

1. Java/Gradle 最初没有自动使用 Windows 系统代理，分发下载卡住。停止仅属于本任务的启动进程，使用本机显式代理后成功；未更改全局代理设置。
2. 第一次隔离启动因 Agent 重复打包 ASM 而失败，进程退出 1。日志见 `raw/20261113T054015Z/baseline-stdout.log`，失败报告为 `integration-20261113T054015Z.json`。
3. Agent 改为 compileOnly ASM，复用 Fabric 启动类路径版本，重新构建后运行通过；没有绕过 Loader 的重复类检查。
4. 收紧初始化失败后的脏状态、清理失败的审计，以及资源异常后的继续加载限制，并增加对应单元测试。
5. 强制重新执行全部构建任务时发现资源展开闭包在执行阶段读取 `Task.project` 的弃用警告；改为配置阶段捕获版本字符串后，重新构建不再出现该警告。

失败记录保留，不能把最初失败的 JAR 哈希与最终通过版本混为一谈。

## 未完成或未验证

- 3 个真实第三方开源 Mod 的兼容矩阵；目前普通 Mod 是本项目编写的标准 Fabric 测试包。
- 在线客户端保持连接、玩家数据与命令树同步。
- Linux 运行验证。
- 任意 Mixin、注册表追加、Access Widener/Class Tweaker、嵌套依赖及完整增量解析。
- 普通 Mod 的停用、更新、完整卸载。
- 受管模块原子更新、通用状态迁移与不合作的停止回调超时隔离。
- 完整堆/类加载器回收证明；受管资源归零不等于所有类已经被 JVM 卸载。

## 恢复及产物检查

字节码恢复在隔离测试进程内实测，不修改最终 JAR。世界保存重启不等于任意坏数据都可自动恢复；出现未知副作用后，仍须正常停服、在额外副本中按一致性备份或模组迁移规则处理。

最终交付 JAR 绑定集成报告中的 SHA256。打包脚本在生成交付包前逐项比对，拒绝打包未经该报告验证的不同版本。
再次使用 `--rerun-tasks` 重新编译和测试后，6 个 JAR 的 SHA256 与运行通过版本完全相同；这证明本机本次重复构建一致，不代表已经验证跨平台构建一致性。汇总见 `build-verification.json`，最终重新构建退出 0，日志为 `raw/rebuild-clean.log`。
初始规划文档与 SHA256 在原始开发环境的 `history/` 中保留，不提交到公开仓库。本报告记录首次本地 P0 验证；后续 GitHub 开源不改变当时的测试范围和结论。
