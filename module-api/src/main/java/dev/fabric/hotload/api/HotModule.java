package dev.fabric.hotload.api;

/** 受管模块必须通过上下文登记资源；任意自建线程不属于此清理契约。 */
public interface HotModule {
    void start(ModuleContext context) throws Exception;

    default void stop() throws Exception {
    }
}
