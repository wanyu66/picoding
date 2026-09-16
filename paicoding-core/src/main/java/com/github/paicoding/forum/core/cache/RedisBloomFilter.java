package com.github.paicoding.forum.core.cache;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** Redis bitmap based Bloom filter. Callers must reuse the same spec for a namespace. */
@Component
public class RedisBloomFilter {
    public Spec spec(String namespace, long expectedInsertions, double falsePositiveProbability) {
        return new Spec(namespace, expectedInsertions, falsePositiveProbability);
    }

    public void add(Spec spec, Object value) {
        for (long offset : offsets(spec, value)) RedisClient.setBit(spec.redisKey(), offset, true);
    }

    public void addAll(Spec spec, Iterable<?> values) {
        for (Object value : values) add(spec, value);
    }

    public boolean mightContain(Spec spec, Object value) {
        for (long offset : offsets(spec, value)) {
            if (!Boolean.TRUE.equals(RedisClient.getBit(spec.redisKey(), offset))) return false;
        }
        return true;
    }

    private long[] offsets(Spec spec, Object value) {
        byte[] input = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        HashCode code = Hashing.murmur3_128().hashBytes(input);
        byte[] bytes = code.asBytes();
        long h1 = toLong(bytes, 0);
        long h2 = toLong(bytes, 8);
        long[] result = new long[spec.hashFunctions];
        for (int i = 0; i < result.length; i++) {
            result[i] = (h1 + (long) i * h2 & Long.MAX_VALUE) % spec.bitSize;
        }
        return result;
    }

    private long toLong(byte[] value, int start) {
        long result = 0L;
        for (int i = start; i < start + 8; i++) result = (result << 8) | (value[i] & 0xffL);
        return result;
    }

    public static final class Spec {
        private final String namespace;
        private final long bitSize;
        private final int hashFunctions;

        private Spec(String namespace, long expectedInsertions, double probability) {
            if (namespace == null || namespace.trim().isEmpty()) throw new IllegalArgumentException("namespace is required");
            if (expectedInsertions <= 0L) throw new IllegalArgumentException("expectedInsertions must be positive");
            if (!(probability > 0D && probability < 1D)) throw new IllegalArgumentException("falsePositiveProbability must be between 0 and 1");
            this.namespace = namespace;
            this.bitSize = Math.max(64L, (long) Math.ceil(-expectedInsertions * Math.log(probability) / (Math.log(2D) * Math.log(2D))));
            this.hashFunctions = Math.max(1, (int) Math.round((double) bitSize / expectedInsertions * Math.log(2D)));
        }

        public String redisKey() { return "bloom:" + namespace; }
        public long getBitSize() { return bitSize; }
        public int getHashFunctions() { return hashFunctions; }
    }
}
