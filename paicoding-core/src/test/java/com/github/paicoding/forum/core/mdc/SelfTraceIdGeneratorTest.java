package com.github.paicoding.forum.core.mdc;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SelfTraceIdGeneratorTest {
    @Test
    void concurrentSequenceAllocationsDoNotOverlapWithinOneCycle() throws Exception {
        Method allocate = SelfTraceIdGenerator.class.getDeclaredMethod("getAutoIncreaseNumber");
        allocate.setAccessible(true);
        Set<Integer> sequence = ConcurrentHashMap.newKeySet();
        IntStream.range(0, 9000).parallel().forEach(i -> {
            try { sequence.add((Integer) allocate.invoke(null)); }
            catch (Exception error) { throw new AssertionError(error); }
        });
        assertEquals(9000, sequence.size());
    }
}
