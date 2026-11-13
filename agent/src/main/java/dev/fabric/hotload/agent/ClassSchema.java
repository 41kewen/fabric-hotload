package dev.fabric.hotload.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

public final class ClassSchema {
    private ClassSchema() {
    }

    public static String fingerprint(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        List<String> shape = new ArrayList<>();
        shape.add("class:" + node.name + ":" + node.superName + ":" + node.access + ":" + node.version);
        node.interfaces.forEach(value -> shape.add("interface:" + value));
        node.fields.forEach(value -> shape.add("field:" + value.name + ":" + value.desc + ":" + value.access));
        node.methods.forEach(value -> shape.add("method:" + value.name + ":" + value.desc + ":" + value.access));
        shape.add("nestHost:" + node.nestHostClass);
        if (node.nestMembers != null) { node.nestMembers.forEach(value -> shape.add("nestMember:" + value)); }
        if (node.permittedSubclasses != null) {
            node.permittedSubclasses.forEach(value -> shape.add("permitted:" + value));
        }
        if (node.recordComponents != null) {
            node.recordComponents.forEach(value -> shape.add("record:" + value.name + ":" + value.descriptor));
        }
        Collections.sort(shape);
        return String.join("\n", shape);
    }

    public static void requireSame(byte[] before, byte[] after) {
        if (!fingerprint(before).equals(fingerprint(after))) {
            throw new IllegalArgumentException("RESTART_REQUIRED: class structure changed");
        }
    }
}
