# Fabric Hotload 项目约定

- 当前目标为 Minecraft 26.2、Java 25、Fabric Loader 0.20.1。
- 使用 Gradle Wrapper；构建与测试入口为 `./gradlew build distribution`。
- Java 使用四空格缩进；新增解释性注释使用中文。
- `module-api` 不引用 Minecraft；`core` 不引用 Fabric 内部类。
- Loader 内部依赖只放在宿主的版本适配类，并拒绝未经验证的 Loader 版本。
- `integration-tests/server*/` 是可抛弃的隔离实例，不修改其他服务器或原始 Mod。
- 原始本地测试的用户已同意在隔离测试服接受 Minecraft EULA；其他环境的操作者须自行审核与同意，不能把历史同意视为授权。CI 不启动 Minecraft 服务器。
- 普通 Mod 加载是实验功能；加载失败后不得谎报回滚或完整卸载。
- 运行结果写入 `verification/`，保留版本、哈希、真实输出和未验证项目。
