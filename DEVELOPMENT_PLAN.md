# Fabric Hotload 开发计划

日期：2026-11-13（Asia/Shanghai）

状态：P0 原型之后已进入 `0.2.0-alpha.1` 多版本适配阶段。当前版本、目标与实际验证结果以 `README.md`、`compatibility/targets.json`、`compatibility/REPORT.md` 为准；以下保留首版设计与后续阶段边界。未完成通用第三方模组兼容或在线玩家验证。

项目名：Fabric Hotload，源码及发布位于 https://github.com/41kewen/fabric-hotload 。

## 1. 目标与边界

目标：在同一个运行中的 Fabric 专用服务器内，尽可能加载新的服务端模组，并逐步支持功能停用与代码更新。

“不重启”验收要求：服务器 JVM 不退出、世界不整体关闭重开、在线测试玩家无需重新连接。允许短暂的受控操作窗口，但必须记录实际 tick 延迟，不能宣称零停顿。

首次部署宿主 Mod、Agent 或 Loader 适配层需要一次正常启动。第一版不解决“向一个未安装本项目的正在运行的服务器直接部署项目”。

两条路线同时保留，但不能混淆覆盖范围：

1. **受管模块路线**：模块使用本项目生命周期与资源管理 API，追求可靠的加载、更新、停用及释放。
2. **普通模组路线**：尽量不修改目标 Mod JAR，通过 Loader 适配、初始化适配和可选 Agent 支持更多普通 Fabric 模组。优先支持新增加载，不默认承诺卸载或更新。

仅支持自有模块不能算完成“兼容普通模组”的目标；必须有普通 Fabric 模组和真实开源第三方模组的对照测试。

第一版明确排除：

- 任意 Mod、任意 Minecraft/Loader 版本的透明兼容。
- 在冻结的原版注册表中通用地追加方块、物品、实体，以及客户端同步改造。
- 任意新增 Mixin、类访问变换（包括旧 Access Widener 和当前 Loader 的 Class Tweaker）的完整运行时应用。
- 通用地迁移世界数据、跨 Mod 对象引用和任意静态状态。
- 不可信 JAR 沙箱。Mod 本身有服务器进程权限，本项目不是安全隔离边界。
- 用同 JVM 内重新创建整个服务器来冒充无缝热加载。

## 2. 首个技术基线

根据用户提出的版本调整，计划基线改为 **Minecraft 26.2、标准 Java 25、Fabric 专用服务器**。官方支持、构建以及 P0 专用测试服已验证；真实第三方模组相容性仍待后续验证。

2026-11-13 查证结果：

| 项目 | 版本/状态 | 证据与后续要求 |
| --- | --- | --- |
| Minecraft | 26.2 正式版 | Mojang 清单含正式版；官方版本 JSON 的 `javaVersion.majorVersion` 为 25 |
| Fabric 游戏支持 | 26.2，stable=true | Fabric Meta 提供对应版本 |
| Fabric Loader | 0.20.1，stable=true | Fabric Meta 的 26.2 加载器列表及官方示例一致；作为首选验证版本 |
| Fabric API | 0.161.0+26.2 | Maven 元数据及官方 26.2 示例一致；作为首选验证版本 |
| Loom | 稳定版 1.19.4 | 已实际构建通过；未采用官方示例中的浮动 SNAPSHOT |
| Gradle Wrapper | 9.8.2 | 已构建通过，分发包与 Wrapper JAR 的官方 SHA256 已核对 |

官方 26.2 示例使用 `net.fabricmc.fabric-loom` 插件、Java release 25，依赖通过 `implementation` 声明，未声明旧版 Yarn/intermediary 映射依赖。必须采用对应模板核对构建流程，不能照搬 1.21.1 的映射和重映射配置。

开工前确认并锁定：

- 确认目标部署确实使用 Minecraft 26.2，而不是其他版本的 Mod 需要移植到 26.2。
- JDK 发行版及补丁版本。
- Fabric Loader、Fabric API、Loom、对应版本的命名/映射需求和 Gradle Wrapper 的相容版本。
- 第一批希望热加载的 3—5 个模组及必要依赖。
- 初始运行环境优先 Linux 专用服务器，Windows 做开发与路径兼容检查；是否具备 Linux 测试环境需确认。

依赖具体版本通过对应官方元数据与构建验证确定，不使用猜测的版本号，不追踪浮动 latest。第一版只维护一个经过测试的版本组合。

建议技术栈：Java、Gradle 多项目构建、Fabric Loom；字节码阶段使用 ASM。Agent 默认在启动时通过 `-javaagent` 加载，避免把动态 Attach 的权限与 JVM 策略差异作为基础能力。

优先采用独立版本适配层，不立即 fork 整个 Fabric Loader。如果技术探针证明无法稳定实现，再记录原因并评估受维护的 Loader 分支。

## 3. 已检查的技术依据

以下是源码/规范观察，不是本项目运行测试结果：

- Fabric Loader 0.20.1 的 `freeze()` 明确冻结模组加载，`load()` 会拒绝冻结后的继续加载。不能通过简单清除标志后重跑启动流程解决所有问题。
- Knot 有增加代码来源、定义类的实现，但“类加载成功”不等于模组元数据、初始化、资源和依赖状态正确。
- Fabric API 26.2 分支的公开 `Event` API 没有通用的监听器注销接口；`ArrayBackedEvent` 将监听器保留在事件结构中。
- Java 25 JVMTI 的普通重定义/重转换仍不允许增删字段与方法、改变方法签名、修饰符和继承结构，也限制部分类属性变化。切换到 26.2/Java 25 不会自动解除这些热替换限制。
- Instrumentation 重定义不会重跑类初始化，不会自动更新已有对象状态；正在执行的旧方法栈帧可以继续执行旧字节码。
- Mixin 有开发用 Agent，但不能据此推导任意新 Mixin 可以在已运行的服务器中完整应用。

本次查阅来源：

- [Mojang 官方 26.2 版本 JSON](https://piston-meta.mojang.com/v1/packages/f28ad96ed5d65e2438da836144a3de15bb9cafe7/26.2.json)
- [Fabric Meta：26.2 的 Loader](https://meta.fabricmc.net/v2/versions/loader/26.2)
- [Fabric API Maven 元数据](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml)
- [Fabric Loom Maven 元数据](https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml)
- [官方 26.2 示例版本配置](https://github.com/FabricMC/fabric-example-mod/blob/26.2/gradle.properties)
- [官方 26.2 示例构建配置](https://github.com/FabricMC/fabric-example-mod/blob/26.2/build.gradle)
- [官方 26.2 示例 Gradle Wrapper](https://github.com/FabricMC/fabric-example-mod/blob/26.2/gradle/wrapper/gradle-wrapper.properties)
- [FabricLoaderImpl，0.20.1](https://github.com/FabricMC/fabric-loader/blob/0.20.1/src/main/java/net/fabricmc/loader/impl/FabricLoaderImpl.java)
- [KnotClassDelegate，0.20.1](https://github.com/FabricMC/fabric-loader/blob/0.20.1/src/main/java/net/fabricmc/loader/impl/launch/knot/KnotClassDelegate.java)
- [Fabric API Event，26.2 分支](https://github.com/FabricMC/fabric-api/blob/26.2/fabric-api-base/src/main/java/net/fabricmc/fabric/api/event/Event.java)
- [ArrayBackedEvent，26.2 分支](https://github.com/FabricMC/fabric-api/blob/26.2/fabric-api-base/src/main/java/net/fabricmc/fabric/impl/base/event/ArrayBackedEvent.java)
- [Java 25 Instrumentation](https://docs.oracle.com/en/java/javase/25/docs/api/java.instrument/java/lang/instrument/Instrumentation.html)
- [Java 25 JVMTI](https://docs.oracle.com/en/java/javase/25/docs/specs/jvmti.html)
- [MixinAgent](https://github.com/SpongePowered/Mixin/blob/master/src/agent/java/org/spongepowered/tools/agent/MixinAgent.java)

这些分支链接和元数据会变化；正式实现时记录实际使用的 tag、commit 和依赖哈希，不把“官方已有支持”当作“本项目已经构建或热加载成功”。先前 1.21.1 规划保存在 `history/DEVELOPMENT_PLAN-1.21.1.md`，原始 SHA256 保存在相邻校验文件中。

## 4. 兼容性模型

每个候选模组分别报告能力，不用单个“兼容”布尔值：

| 能力 | 含义 |
| --- | --- |
| load | 在启动完成后新增加载，实际目标行为生效 |
| disable | 功能停止，不承诺类已卸载 |
| update | 新版本行为生效，满足规定的状态和资源迁移要求 |
| release | 可释放受管资源及类加载器引用；不承诺 JVM 立即完成 GC |

每项能力状态使用：`SUPPORTED`、`ADAPTER_REQUIRED`、`EXPERIMENTAL`、`RESTART_REQUIRED`、`UNKNOWN`。`UNKNOWN` 不允许自动进入生产激活流程。

判定需结合：

- `fabric.mod.json`、环境、入口点、依赖/冲突关系、嵌套 JAR。
- Mixin 配置及目标类是否已加载。
- Access Widener/对应 Loader 的类访问变换声明。
- 字节码中的已知注册表、事件、命令和生命周期使用。
- 线程、执行器、静态缓存、网络通道、资源和持久化操作。
- 适配器声明、已有测试及当前加载状态。

静态扫描只能发现部分风险，不能证明任意 Java 代码安全。反射、动态生成类和初始化间接调用都可能绕过扫描。因此扫描结果不能直接升级为“已经兼容”。

兼容矩阵绑定 Minecraft/Loader/API/JDK 版本、模组版本与 SHA256、依赖集合和已验证能力。不能从单个版本测试推导所有版本可用。

## 5. 拟议组件及职责

以下是整体拟议结构；当前已实现 API、核心运行时、宿主、Loader 适配探针、Agent 和测试，其他阶段不创建占位实现：

```text
fabric-hotload/
  module-api/          受管模块的生命周期、资源句柄和状态传递
  core/                操作队列、状态机、依赖图、日志与能力模型
  fabric-host/         宿主 Mod、服务器线程入口、管理命令、事件桥接
  loader-adapter/      绑定特定 Loader 版本的增量加载与元数据适配
  agent/               可选 Instrumentation 与字节码变换
  compat-adapters/     有明确范围和测试的普通模组/功能适配器
  test-mods/           正向、边界和失败场景的最小真实模组
  integration-tests/   独立测试服、在线客户端与验证脚本
  docs/                安装、兼容矩阵、限制、恢复和开发文档
```

### 5.1 受管模块 API

提供明确的 `start`、`stop` 生命周期和可选状态导出/导入契约。启动应通过上下文登记资源，资源具有所有者和可关闭句柄。

事件、命令、定时任务和执行器通过宿主提供。停用时先阻止新调用，再等待已进入的调用退出，最后关闭资源并清除引用。

游戏/Fabric/宿主公共 API 由父加载器提供，模块私有实现使用独立加载器。禁止重复定义公共 API 或同名游戏类；跨模块依赖有明确的解析与所有权规则。

独立加载器是受管模块的默认策略，不假设所有第三方 Mod 都可这样运行。

### 5.2 普通模组加载适配

不全局重跑 `FabricLoader.load()`，不重放所有已加载模组的初始化。候选集与现有集合做增量依赖检查，拒绝重复 ID、冲突和不满足依赖的候选。

只处理适用的 `main`/`server` 入口点及相应语言适配器；第一版支持范围需由探针确定。`client` 不执行；已错过的 `preLaunch` 等启动阶段不假装成功补跑。

元数据、`isModLoaded`、`getModContainer`、入口点、嵌套依赖和代码可见性必须一起验证。不得只让模组列表显示存在，却未建立其实际需要的 Loader 状态。

进入 Knot 的类通常不能逐个卸载，更新同名类也不能靠重复添加 JAR 解决。普通模组初期允许发布“仅 load 支持”的明确结果。

### 5.3 事件与命令桥接

受管模块使用稳定的宿主桥接，桥接内部维护可变订阅者，停用后移除实际回调引用。避免每次重载向 Fabric 事件永久追加新包装器。

普通模组直接注册 Fabric 事件时，需要特定适配器或经验证的注册拦截；准确保留事件 phase、顺序、返回值及短路语义。无法撤销的监听器不得宣称支持完整更新。

第一版受管命令优先挂在宿主固定命令根下。普通模组的原生命令需要单独处理命令注册时机、删除和在线玩家命令树同步，不能简单调用启动回调代替。

### 5.4 Agent 与 Mixin

先做已有方法体更新，不做通用 Mixin 转换器。每次变换记录目标类、定义加载器、结构指纹、已有变换和可恢复字节码。

必须保留 Knot、Mixin 及其他 Transformer 的已有变换，不能用未经原有处理的原版字节码覆盖运行中的类。恢复字节码也不等于恢复已经发生的游戏副作用。

对无结构变化的少量注入再研究外部辅助方法/稳定分派桥接。新增成员、接口、无法解决的目标类访问变换、冲突注入和未验证类型默认要求重启。

不把扩展 HotSwap JVM 作为普通用户默认依赖；可在后续单独研究，单列支持条件。

## 6. 操作流程与失败语义

```text
读取候选 → 哈希与格式检查 → 依赖/能力分析 → dry-run 报告
       → 暂存准备 → 服务器安全点激活 → 行为验证 → 发布最终状态
```

- 文件解析和重映射在不访问游戏状态的后台阶段完成；首次类定义、初始化和游戏状态修改作为可能不可逆的提交阶段记录。
- 激活、停用与更新通过单一操作队列串行执行；游戏状态操作切回服务器线程。
- 安全点策略必须让需要停止的旧回调退出。超时不使用强制杀线程，不把不合作的任务当作已停止。
- 管理命令限制为服务器控制台或经版本适配的最高管理权限；候选路径限制在专用目录，并处理规范化、链接逃逸、ZIP 路径与解压大小。
- 依赖模块不可随意停用。更新涉及其他模块引用时，需要有支持的联合迁移策略，否则拒绝。

操作状态至少区分：

- `REJECTED`：检查阶段拒绝，尚未进行激活。
- `ACTIVE`：规定的行为验证通过。
- `DISABLED`：停止功能，仍可能保留已定义类。
- `FAILED_CLEAN`：记录范围内的资源已成功清理，不扩展为“没有任何未知副作用”。
- `DIRTY_RESTART_REQUIRED`：存在残余资源、不可逆注册或未知副作用，需要重启恢复。

可回退的受管更新：准备新实例 → 暂停旧分派 → 等待旧调用退出 → 按契约迁移状态 → 切换实例 → 清理旧资源。状态使用明确的数据格式和版本，不直接把旧加载器的任意对象交给新模块。

不是所有更新都能回退；若状态迁移、世界写入或初始化已有不可逆副作用，必须保留旧/新版本与操作记录并报告恢复要求。

## 7. 阶段与进入条件

### P0：技术探针，先决定路线能否成立

只做最小实验，不先堆完整框架。

1. 宿主在启动完成后加载一个受管模块，提供可观察命令和事件行为；停用后确实停止。
2. 加载一个普通结构的 Fabric 测试 Mod，不使用本项目模块 API；验证入口点、依赖可见性、元数据查询和实际行为。
3. 对服务器已加载类做一次无结构变化的方法体替换，再恢复；验证已有 Mixin 不被破坏，并记录旧栈帧/状态限制。
4. 对一个增加成员的变更做负向测试：应拒绝或按 JVM 实际错误失败，不损坏服务器状态。

产物：固定版本清单、测试模组 JAR 与哈希、完整命令/输出/退出状态、服务器日志、探针结论。

进入条件：普通 Mod 探针不能只成功添加类路径，必须证明 Loader 查询与功能一致。若不能成立，暂停扩大普通模组能力，先决定适配层修正或 Loader fork，而非把受管模块测试当作替代成功。

### P1：可靠的受管模块 MVP

实现生命周期、资源所有权、串行操作、持久操作记录和管理命令。

第一批命令：`/hotmods inspect`、`load`、`list`、`status`、`disable`、`reload`；参数与能力检查在实现前固化。`reload` 只对明确支持 update 的目标开放。不提供会误导用户的通用 `unload`。

测试模块包括：无状态命令、事件计数器、带版本化状态的模块、启动异常、停止超时、循环依赖和重复 ID。

进入条件：连续 100 次受管加载/更新/停用，旧代回调不再执行、宿主登记的资源数量回到基线、无重复调用，在线客户端保持连接。

### P2：普通 Fabric 模组的增量加载

支持测试证实的入口点、依赖和代码加载路径；注册表/Mixin/启动阶段依赖在不支持时明确拒绝。

选择至少 3 个真实开源第三方服务端模组，保留原 JAR，不以修改它们接入模块 API 冒充通用兼容。每个模组在干净服务器上进行“正常启动安装”和“启动后加载”的同版本行为对照。

分别记录能否 load、disable、update；只支持 load 的模组也是有效覆盖，但不能包装成完整重载。

### P3：扩大适配器覆盖

优先按收益增加：Fabric 常用事件、命令、晚加入玩家初始化、资源/数据重载协作、已知模组的清理契约。

不得向所有旧订阅者重新广播服务器启动事件。适配只补齐目标模组已经验证的初始化需求。

每个适配器绑定版本范围、处理的副作用、拒绝条件和集成测试。用户明确需要哪些模组后，按实际兼容矩阵确定开发顺序。

### P4：有限字节码热更新

先发布方法体更新能力，再研究限定注入类型。发生结构变化、无法保留已有变换或模块依赖迁移不受支持时拒绝。

Mixin 覆盖必须逐类型、逐目标类测试，不能凭“没有新增字段”就判定可重载，也不能从单一注入成功推广到整个模组。

### P5：开源 alpha 发布

整理安装、初次启动需求、能力矩阵、失败恢复、贡献规范和示例；打包宿主与 Agent，提供校验值和可复现构建说明。

建议使用 Apache-2.0，最终许可证在建仓库时确定。复制或修改上游代码前核对其实际 LICENSE，保留必要的版权与第三方通知。

只有用户要求后才创建远程仓库或发布。Minecraft 官方服务器 JAR、真实世界与用户配置不提交仓库；集成环境遵守其下载与 EULA 要求。

## 8. 验证与恢复要求

所有运行验证在隔离测试服进行，不操作真实生产服务器。先观察正常启动版本，再运行热加载版本。

每份测试记录至少包含：版本组合、目标 JAR SHA256、命令与输入、原始输出、退出状态或服务端命令结果、实际功能差异、资源清理结果与剩余缺项。

| 场景 | 验收重点 |
| --- | --- |
| 启动后新增模块/Mod | JVM 与世界持续运行，目标行为实际生效 |
| 玩家在线操作 | 连接保持，命令树/目标行为更新正确，记录 tick 延迟 |
| 循环更新 | 无重复监听、任务叠加、旧版本调用；资源回到规定基线 |
| 依赖缺失/冲突 | 有具体拒绝原因，不触发初始化 |
| 初始化异常/停止超时 | 正确报告清理结果或需要重启，不伪造回滚 |
| 新增成员/冻结注册表 | 已知不支持路径受到检查；漏检的实验失败记录残余状态 |
| 保存后正常重启 | 世界与相关玩家数据可读，已测试的持久状态一致 |
| 多模组共存 | 现有模组功能与已有 Mixin 保持正确，无错误重初始化 |

资源数量检查不等于证明所有对象已被 GC。类加载器可达性、线程、堆和文件句柄采用额外诊断；不以一次强制 GC 结果承诺完整卸载。

恢复设计：

- 不覆盖原始 Mod JAR；原版与新版本按哈希保存，派生文件写入本项目工作目录。
- 隔离测试世界保留可恢复基线。生产使用另行制定一致性备份/快照流程，不在正在写入时随意复制世界文件。
- 记录已经执行的初始化、资源注册及字节码操作。字节码可恢复时也明确说明其不能撤销游戏状态变更。
- 出现脏状态后阻止继续叠加更新，输出需要停服恢复的步骤。
- 实现恢复脚本后，在额外的测试副本上验证，保留用于交付检查的最终版本。

## 9. 第一个公开版本的完成标准

必须同时具备：

1. 一套固定版本组合上的可构建宿主项目及安装文档。
2. 受管模块的加载、停用、受限更新与真实行为测试。
3. 普通 Fabric 测试 Mod 及真实第三方 Mod 的增量加载证据。
4. 可查询的逐项能力、拒绝理由和失败状态。
5. 原始哈希、操作日志、世界保存重启验证和经验证的恢复流程。
6. 明确列出的不支持场景，不宣称任意 Mod 热加载或无副作用回滚。

不预先承诺兼容百分比或完整工期；先完成 P0，再据实际技术结果估算 P1—P5。

## 10. 下一步执行顺序

1. 以 Minecraft 26.2/Java 25 为基线，验证并锁定上述候选工具链，收集原生适配 26.2 的优先模组名单。
2. 创建真正的 Gradle 项目、锁定版本、建立干净测试服务器。
3. 按 P0 实现并运行三个正向探针和一个结构变化负向探针。
4. 将探针结果写成第一份兼容报告，再决定 Loader 适配细节与 MVP 范围。

## 11. P0 实施进度（2026-11-13）

- 已创建本地 Git/Gradle 项目，采用 26.2、JDK 25、Loader 0.20.1、API 0.161.0+26.2、Loom 1.19.4 和 Gradle 9.8.2。
- 已实现受管模块加载/停用/停用后版本切换；10 项单元测试通过。
- 普通结构的 Fabric 测试 Mod 已完成正常启动与启动后加载对照：入口点、资源和 tick 回调实际生效。
- 在同一 JVM/服务器 session 中完成 100 次受管加载/更新/停用循环，受管资源回到零。
- 已验证自身测试类的方法体变换、已有启动 Mixin 保留、结构变更拒绝和字节码恢复。
- 已验证普通 Mod 初始化失败时标记脏状态并阻断继续加载；隔离世界保存后可重新启动。
- 真实第三方模组、在线客户端、Linux、完整类卸载和任意注册表/Mixin 改造仍未验证。

运行证据绑定实际 JAR SHA256，见 `verification/REPORT.md` 与 `verification/integration-20261113T054723Z.json`。初次 P0 验证未触碰其他服务器或用户世界。

源码仓库：[41kewen/fabric-hotload](https://github.com/41kewen/fabric-hotload)。GitHub CI 仅构建与运行单元测试，不把它的通过结果当作完整服务器行为验证。
