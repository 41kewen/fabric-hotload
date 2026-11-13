package dev.fabric.hotload.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ManagedContextTest {
    @Test
    void closesInReverseOrderAndContinuesAfterFailure() {
        var closed = new ArrayList<Integer>();
        var context = new ManagedContext(message -> { });
        context.own(() -> closed.add(1));
        context.own(() -> { closed.add(2); throw new IllegalStateException("close failed"); });
        context.own(() -> closed.add(3));
        assertThrows(IllegalStateException.class, context::close);
        assertEquals(List.of(3, 2, 1), closed);
        assertEquals(0, context.resourceCount());
        context.close();
        assertEquals(List.of(3, 2, 1), closed);
    }
}
