# Fabric Hotload — 26.2 P0

一个开源的 **Fabric 专用服务器运行时加载实验项目**。当前实现技术探针与最小受管模块运行时，不是“所有 Mod 都可热重载”的生产工具。

源码仓库：[41kewen/fabric-hotload](https://github.com/41kewen/fabric-hotload) · [构建与单元测试](https://github.com/41kewen/fabric-hotload/actions/workflows/build.yml)

## 当前实现

- 候选 JAR 路径/大小/描述符检查，原始 SHA256 和按哈希保存的额外副本。
- 受管模块：独立类加载器、宿主子命令、tick 回调、资源所有权、停用与版本切换。
- 普通 Fabric Mod：绑定 Loader 0.20.1 的实验增量加载，保留 frozen，不重新初始化现有 Mod。
- 普通 Mod 测试包不依赖受管 API；检查真实 Loader 容器、入口点、资源读取和 tick 回调。
- 可选启动 Agent：仅修改自己的测试类中的方法体，并验证启动时已有 Mixin 保留、结构变更拒绝和字节码恢复。
- 失败日志和脏状态；未受管副作用不能自动撤销，不伪造通用回滚。

具体运行结果与未验证项见 `verification/REPORT.md` 和同目录的机器可读报告。

## 固定基线

| 组件 | 版本 |
| --- | --- |
| Minecraft | 26.2 |
| JDK | 25；开发机器使用 Oracle 25.0.4.1 |
| Fabric Loader | **0.20.1**；内部适配严格检查该版本 |
| Fabric API | 0.161.0+26.2 |
| Fabric Loom | 1.19.4 |
| Gradle Wrapper | 9.8.2，固定分发包 SHA256 |

首次安装宿主需要一次正常启动；不支持直接向一个未安装本项目的运行中服务器部署宿主。

## 构建

配置 `JAVA_HOME` 指向 JDK 25，再运行：

```sh
./gradlew build distribution
```

Windows 使用 `gradlew.bat`。输出在 `build/distribution/`：

- `fabric-hotload-host-0.1.0-p0.jar`：宿主，放入测试服 `mods/`。
- `fabric-hotload-agent-0.1.0-p0.jar`：可选 Agent，放在服务器目录，通过启动参数加载。
- `candidates/`：两个受管模块版本、一个普通 Fabric 测试 Mod，以及独立的初始化失败测试包。

Agent 不重复打包 ASM，依赖 Fabric 服务器启动类路径中的 ASM。不要单独作为通用应用 Agent 使用。

需要网络代理时用本机环境变量或 JVM 参数，不把代理凭据写入项目。Windows 系统代理不会自动成为 Gradle/JVM 的代理；本次开发对此做过显式配置。

## 安装与手动验证

只在备份后的隔离测试服使用。安装正常的 Fabric 26.2 服务器、对应 Fabric API，以及宿主 JAR。

```sh
java -javaagent:fabric-hotload-agent-0.1.0-p0.jar \
  -Dhotload.experimentalMods=true -Xmx1G \
  -jar fabric-server-launch.jar nogui
```

不做 Agent 探针时可省略 `-javaagent`。普通 Mod 增量加载默认关闭，必须显式设置 `hotload.experimentalMods=true`。

服务器启动后把测试 JAR **复制**到 `hotmods/candidates/`，分别命名为 `demo-v1.jar`、`demo-v2.jar`、`ordinary-probe.jar`。不要把两个受管版本都放入 `mods/`。

控制台命令如下；游戏内加 `/`，需要 `COMMANDS_OWNER` 权限：

```text
hotmods status
hotmods inspect demo-v1.jar
hotmods load demo-v1.jar
hotmods exec demo_counter hello
hotmods reload demo-v2.jar
hotmods exec demo_counter hello
hotmods disable demo_counter

hotmods ordinary
hotmods loadmod ordinary-probe.jar
hotmods ordinary

hotmods patch patch
hotmods patch negative
hotmods patch restore
```

操作记录写入 `hotmods/operations.jsonl`，额外 JAR 副本存入 `hotmods/store/<sha256>.jar`。初始化返回仅报告 `STARTED_UNVERIFIED`，实际功能仍需验证，不能凭启动提示认定兼容。

## 受管模块契约

JAR 包含 `hotload-module.properties`：

```properties
id=my_module
version=1.0.0
entrypoint=example.MyModule
```

入口类实现 `module-api` 中的 `HotModule`。使用 `ModuleContext.command`、`onTick` 和 `own` 登记资源；生命周期、回调及句柄关闭都在服务器线程串行执行。

目前 `reload` 是**检查并暂存 → 停用旧版本 → 加载新版本**，不是原子状态迁移。新的模块实例状态重置；如果新版本启动失败，旧版本不会自动恢复，且可能要求重启。

不要创建未受管线程、在静态变量中向外暴露模块对象，或让 `stop`/资源关闭永久阻塞。P0 不提供任意模块代码的强制超时隔离和安全沙箱。

## 普通 Mod 的限制

- 仅支持标准 schema 1、默认语言适配器与 `main`/`server` 入口点的实验加载。
- 当前拒绝声明 Mixin、类访问变换、嵌套 JAR、自定义语言适配器、未适配入口点或同名类冲突的候选。
- 检查已安装依赖、版本要求与双方显式冲突，但还不是完整增量 ModResolver。
- 没有完整的字节码风险分析。没有相关元数据不代表不会调用冻结注册表或错过启动事件。
- 不补跑服务器启动事件，不自动同步新增命令，不补齐任意资源/数据包/网络通道集成。
- 普通 Mod **仅 load 实验支持**；没有通用 disable/update/unload。
- 进入 Knot 的类路径、入口点和初始化副作用不可通用撤销。提交后失败会标记 `DIRTY_RESTART_REQUIRED`，阻止继续加载。
- 动态加载不等于安装持久化：不会把候选写入 `mods/`，重启后不会自动重放操作日志。

## 自动验证

Python 3 标准库即可运行，没有额外 Python 依赖：

```sh
python integration-tests/run_probes.py --java-home /path/to/jdk-25 --accept-eula
```

`--accept-eula` 表示操作者已阅读并接受 [Minecraft EULA](https://www.minecraft.net/eula)。脚本创建新的本地隔离实例，绑定 `127.0.0.1`，不开 RCON；不会覆盖已有服务器目录。

可选 `--proxy http://host:port`、`--minecraft-server /path/to/official/server.jar`、`--cycles 100`。缓存服务器 JAR 会与官方 SHA1 校验。

验证顺序：正常启动安装普通探针 → 启动后加载同一探针 → 受管模块更新/停用及循环测试 → Agent 变换/拒绝/恢复 → 保存并重新启动同一个测试世界。

当前自动验证没有真实客户端连接；不能据此宣称在线玩家必然不断线。未覆盖 Linux、真实第三方 Mod 和完整堆/类加载器回收证明。

源码/JAR/记录打包入口为 `scripts/package_release.py`，必须传入成功的集成报告；脚本核对分发 JAR 哈希，并拒绝覆盖已有交付包：

```sh
python scripts/package_release.py --report verification/integration-20261113T054723Z.json --output /path/to/fabric-hotload-26.2-p0.zip
```

## 故障恢复

1. 遇到 `DIRTY_RESTART_REQUIRED`，不要继续叠加加载；保留日志与候选哈希。
2. 正常保存并关闭测试服务器。不要在服务器写入时直接覆盖世界文件。
3. 在额外副本中正常启动，宿主不会自动重放此前动态激活。
4. 若模组已修改世界数据，只能使用正常迁移流程或一致性备份恢复；停用或恢复字节码不会撤销这些数据变化。

Agent 的 `patch restore` 仅恢复自身测试类字节码，不是世界状态回滚工具。

## 许可证与后续

项目采用 Apache-2.0；第三方条款见 `THIRD_PARTY_NOTICES.md`。当前是 P0 实验阶段，不分发 Minecraft 服务器。

GitHub Actions 在 Linux/Windows 上构建并运行单元测试，不自动接受 Minecraft EULA、不启动服务器。服务器行为验证仍以已有报告为准；CI 构建产物不能替代运行兼容性测试。

后续优先：真实第三方服务端 Mod 兼容矩阵、事件与命令适配、状态迁移、操作窗口/性能测量。设计与阶段边界见 `DEVELOPMENT_PLAN.md`。
