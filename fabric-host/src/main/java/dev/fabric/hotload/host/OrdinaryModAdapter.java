package dev.fabric.hotload.host;

import dev.fabric.hotload.core.JarInspection;

/** 普通 Mod 的不可撤销加载与受管模块生命周期分开。 */
interface OrdinaryModAdapter {
    boolean dirty();

    String description();

    String load(JarInspection inspection) throws Exception;
}
