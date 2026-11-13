package dev.fabric.hotload.agent;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

class ClassSchemaTest {
    private static byte[] sample(String value) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "test/Target", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "value", "()Ljava/lang/String;", null, null);
        method.visitCode();
        method.visitLdcInsn(value);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void permitsMethodBodyChange() {
        assertDoesNotThrow(() -> ClassSchema.requireSame(sample("old"), sample("new")));
    }

    @Test
    void rejectsAddedField() {
        byte[] before = sample("old");
        ClassNode node = new ClassNode();
        new ClassReader(before).accept(node, 0);
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "added", "I", null, null));
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        assertThrows(IllegalArgumentException.class, () -> ClassSchema.requireSame(before, writer.toByteArray()));
    }
}
