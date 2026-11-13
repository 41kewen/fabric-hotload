package dev.fabric.hotload.agent;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;

/** P0 仅允许变换自身的 ProbeTarget，不开放任意类改写接口。 */
public final class HotloadAgent {
    public static final String TARGET = "dev.fabric.hotload.host.ProbeTarget";
    private static final Map<ClassLoader, byte[]> BASELINES = new WeakHashMap<>();
    private static Instrumentation instrumentation;

    private HotloadAgent() {
    }

    public static void premain(String arguments, Instrumentation instance) {
        instrumentation = instance;
        instance.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                                    ProtectionDomain domain, byte[] bytes) {
                if (TARGET.replace('.', '/').equals(name) && redefining == null) {
                    synchronized (BASELINES) { BASELINES.put(loader, bytes.clone()); }
                }
                return null;
            }
        }, false);
        System.out.println("[HotloadAgent] installed redefine=" + instance.isRedefineClassesSupported());
    }

    public static synchronized String probe(String mode) throws Exception {
        if (instrumentation == null || !instrumentation.isRedefineClassesSupported()) {
            throw new IllegalStateException("Startup Agent is absent or redefine is unsupported");
        }
        Class<?>[] targets = Arrays.stream(instrumentation.getAllLoadedClasses())
                .filter(type -> type.getName().equals(TARGET)).toArray(Class<?>[]::new);
        if (targets.length != 1) {
            throw new IllegalStateException("Expected exactly one loaded probe target, got " + targets.length);
        }
        Class<?> target = targets[0];
        byte[] baseline;
        synchronized (BASELINES) { baseline = BASELINES.get(target.getClassLoader()); }
        if (baseline == null || !instrumentation.isModifiableClass(target)) {
            throw new IllegalStateException("No captured post-Knot baseline or target is unmodifiable");
        }
        ClassNode node = new ClassNode();
        new ClassReader(baseline).accept(node, 0);
        if (mode.equals("negative")) {
            node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "hotloadAddedField", "I", null, null));
            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            byte[] invalid = writer.toByteArray();
            try {
                ClassSchema.requireSame(baseline, invalid);
                throw new IllegalStateException("Schema guard failed to detect an added field");
            } catch (IllegalArgumentException expected) {
                // 独立探针继续验证 JVM 本身拒绝；生产变换应在 schema guard 处终止。
            }
            try {
                instrumentation.redefineClasses(new ClassDefinition(target, invalid));
            } catch (UnsupportedOperationException expected) {
                return "STRUCTURE_REJECTED_BY_GUARD_AND_JVM " + expected.getClass().getSimpleName();
            }
            instrumentation.redefineClasses(new ClassDefinition(target, baseline));
            throw new IllegalStateException("Unexpected JVM acceptance of structural change");
        }
        byte[] replacement = baseline;
        if (mode.equals("patch")) {
            int replacements = 0;
            for (var method : node.methods) {
                if (method.name.equals("value") && method.desc.equals("()Ljava/lang/String;")) {
                    for (var instruction : method.instructions) {
                        if (instruction instanceof LdcInsnNode constant && constant.cst.equals("original")) {
                            constant.cst = "patched";
                            replacements++;
                        }
                    }
                }
            }
            if (replacements != 1) { throw new IllegalStateException("Unexpected probe method bytecode"); }
            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            replacement = writer.toByteArray();
        } else if (!mode.equals("restore")) {
            throw new IllegalArgumentException("Agent probe mode must be patch, restore or negative");
        }
        ClassSchema.requireSame(baseline, replacement);
        instrumentation.redefineClasses(new ClassDefinition(target, replacement));
        return "BYTECODE_" + mode.toUpperCase(java.util.Locale.ROOT) + " schema=unchanged";
    }
}
