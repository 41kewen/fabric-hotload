# Fabric Hotload 项目约定

- 版本组合的唯一配置源为 `compatibility/targets.json`；默认目标仍为 26.2。
- 使用 Gradle Wrapper；构建入口为 `./gradlew build distribution -Ptarget=<目标>`，Gradle/Loom 本身使用 JDK 25。
- 公共运行时/Agent/受管示例按 Java 17 编译；宿主和普通探针按目标的 Java 17/21/25 编译。
- 旧版 Minecraft 使用 remap Loom 与官方映射，分发 `remapJar`；26.x 分发未混淆 `jar`。
- 每个目标的中间文件独立，产物位于 `build/targets/<目标>/distribution/`，由 `distribution.json` 绑定版本和 SHA256。
- Python 边界测试：`python -m unittest discover -s integration-tests -p 'test_*.py' -v`；服务器探针使用 `--target`，矩阵入口为 `scripts/run_matrix.py`。
- 发布工具测试：`python -m unittest discover -s scripts -p 'test_*.py' -v`；`scripts/summarize_matrix.py` 核对基线与已测 JAR 后合并分批记录，不删除原始失败报告。
- Java 使用四空格缩进；新增解释性注释使用中文。
- `module-api` 不引用 Minecraft；`core` 不引用 Fabric 内部类。
- Loader 内部依赖只放在 `Loader0201Adapter`；未知 Loader 禁用普通 Mod 热加载，不因此禁用受管模块。不能只放宽元数据就宣称兼容。
- `integration-tests/server*/` 是可抛弃的隔离实例，不修改其他服务器或原始 Mod。
- 原始本地测试的用户已同意在隔离测试服接受 Minecraft EULA；其他环境的操作者须自行审核与同意，不能把历史同意视为授权。CI 不启动 Minecraft 服务器。
- 普通 Mod 加载是实验功能；加载失败后不得谎报回滚或完整卸载。
- 运行结果写入 `verification/`，保留版本、哈希、真实输出和未验证项目。
