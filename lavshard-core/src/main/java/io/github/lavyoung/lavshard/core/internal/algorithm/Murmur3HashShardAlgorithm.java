package io.github.lavyoung.lavshard.core.internal.algorithm;

import io.github.lavyoung.lavshard.core.api.algorithm.AlgorithmConfig;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardAlgorithm;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardBucket;
import io.github.lavyoung.lavshard.core.api.algorithm.ShardValue;
import io.github.lavyoung.lavshard.core.api.exception.ShardAlgorithmException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 基于 MurmurHash3 x86 32-bit 的固定逻辑桶算法。
 *
 * <h2>职责边界</h2>
 * <p>本算法只完成“分片键 → 逻辑桶”的确定性映射，不选择数据源、分片节点或物理表。
 * 完整路由分为两层：</p>
 * <pre>
 * ShardValue --本算法--&gt; ShardBucket --ShardTopology--&gt; ShardNode
 * </pre>
 * <p>扩容时只调整“逻辑桶 → 物理节点”的拓扑映射，不改变分片键所属的逻辑桶，
 * 因而无需因物理节点数量变化而重新映射全部数据。</p>
 *
 * <h2>{@code murmur3_32_v1} 持久化协议</h2>
 * <ul>
 *   <li>字符串：{@code 0x01 + UTF-8 原始字节}，不裁剪、不转换大小写。</li>
 *   <li>整数：{@code 0x02 + 8 字节大端有符号 long}。</li>
 *   <li>UUID：{@code 0x03 + 高 64 位 + 低 64 位}，两个 long 均采用大端编码。</li>
 *   <li>二进制：{@code 0x04 + 原始字节}。</li>
 *   <li>Hash：MurmurHash3 x86 32-bit，seed 固定为 {@code 0}。</li>
 *   <li>逻辑桶：{@code floorMod(hash, bucketCount)}。</li>
 * </ul>
 * <p>类型标记用于保留业务类型语义，例如字符串 {@code "1"} 与整数 {@code 1L}
 * 会产生不同的 Hash 输入。{@link Math#floorMod(int, int)} 保证负 Hash（包括极端边界值）
 * 仍映射到 {@code [0, bucketCount)}。</p>
 *
 * <h2>计算示例</h2>
 * <pre>
 * 123456789L
 *   -规范化-&gt; 02 00 00 00 00 07 5B CD 15
 *   -Murmur3-&gt; -633827586
 *   -floorMod(hash, 1024)-&gt; ShardBucket(766)
 * </pre>
 *
 * <h2>选择 MurmurHash3 的原因</h2>
 * <ul>
 *   <li>主要由整数乘法、旋转和异或组成，适合高频路由热路径。</li>
 *   <li>雪崩效应和分布性优于直接依赖不同 Java 类型各自的 {@code hashCode()}。</li>
 *   <li>算法定义明确，便于 Java、Go、Python 等实现复现相同的桶编号。</li>
 *   <li>32 位结果足以映射有限数量的逻辑桶；本场景不需要 128 位 Hash 的额外空间。</li>
 * </ul>
 * <p>MurmurHash3 是非加密 Hash，只用于数据分布，不得用于密码、签名、令牌或防篡改。
 * Hash 碰撞仅表示不同键落入同一逻辑桶，不代表数据库记录发生冲突。</p>
 *
 * <h2>兼容性警告</h2>
 * <p>{@code HASH_VERSION}、seed、类型标记、字符编码、字节序、Murmur3 变体以及取模方式
 * 共同决定数据位置。已有数据后不得修改 {@code murmur3_32_v1} 的任何语义；需要演进时必须
 * 新增版本，并通过固定向量测试和数据迁移流程发布。</p>
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @data 2026/9/10
 */
public final class Murmur3HashShardAlgorithm implements ShardAlgorithm {

    public static final String NAME = "hash_mod";
    public static final String HASH_VERSION = "murmur3_32_v1";

    private static final int HASH_SEED = 0;

    private static final byte TEXT_MARKER = 0x01;
    private static final byte LONG_MARKER = 0x02;
    private static final byte UUID_MARKER = 0x03;
    private static final byte BINARY_MARKER = 0x04;

    @Override
    public String name() {
        return NAME;
    }

    /**
     * 将类型明确的分片键稳定映射到配置范围内的逻辑桶。
     *
     * <p>执行顺序固定为：规范化输入、计算 32 位 Hash、使用 {@code floorMod} 取桶。
     * 算法版本不匹配时立即拒绝，避免使用错误协议访问已有数据。</p>
     *
     * @param value  分片键值
     * @param config 算法配置
     * @return 范围为 {@code [0, bucketCount)} 的逻辑桶
     * @throws NullPointerException    value 或 config 为 null 时抛出
     * @throws ShardAlgorithmException Hash 版本不受支持时抛出
     */
    @Override
    public ShardBucket calculate(ShardValue value, AlgorithmConfig config) {
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(config, "config must not be null");

        if (!HASH_VERSION.equals(config.hashVersion())) {
            throw new ShardAlgorithmException("unsupported hashVersion: " + config.hashVersion());
        }

        // 第一步：把不同 Java 类型转换成带类型标记的稳定字节。
        byte[] canonicalizeValue = canonicalize(value);

        // 第二步：使用固定变体和固定 seed 生成可跨进程复现的 32 位 Hash。
        int hash = murmur3Hash32(canonicalizeValue, HASH_SEED);

        // 第三步：将可能为负数的 Hash 安全映射到 [0, bucketCount)。
        int bucket = Math.floorMod(hash, config.bucketCount());

        return new ShardBucket(bucket);
    }

    /**
     * 按照 {@code murmur3_32_v1} 契约生成带类型标记的稳定字节。
     *
     * <p>规范化必须与具体 Java 对象的 {@code hashCode()} 和默认字符集无关，否则不同类型、
     * 不同进程或不同语言实现可能得到不同的路由结果。</p>
     *
     * @param value 分片键值
     * @return 规范化后的字节
     * @throws ShardAlgorithmException 值类型不受支持时抛出
     */
    private static byte[] canonicalize(ShardValue value) {
        if (value instanceof ShardValue.TextValue textValue) {
            return prepend(TEXT_MARKER, textValue.value().getBytes(StandardCharsets.UTF_8));
        }

        if (value instanceof ShardValue.LongValue longValue) {
            byte[] payload = ByteBuffer.allocate(Long.BYTES)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putLong(longValue.value())
                    .array();
            return prepend(LONG_MARKER, payload);
        }

        if (value instanceof ShardValue.UuidValue uuidValue) {
            byte[] payload = ByteBuffer.allocate(Long.BYTES * 2)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putLong(uuidValue.value().getMostSignificantBits())
                    .putLong(uuidValue.value().getLeastSignificantBits())
                    .array();
            return prepend(UUID_MARKER, payload);
        }

        if (value instanceof ShardValue.BinaryValue binaryValue) {
            return prepend(BINARY_MARKER, binaryValue.value());
        }

        throw new ShardAlgorithmException("unsupported shard value type: " + value.getClass().getName());
    }

    /**
     * 在值字节前增加类型标记，避免不同业务类型共享完全相同的 Hash 输入。
     *
     * @param marker  类型标记
     * @param payload 规范化后的值字节
     * @return 类型标记与值字节组成的新数组
     */
    private static byte[] prepend(byte marker, byte[] payload) {
        byte[] result = new byte[payload.length + 1];
        result[0] = marker;
        System.arraycopy(payload, 0, result, 1, payload.length);
        return result;
    }

    /**
     * 计算标准 MurmurHash3 x86 32-bit。
     *
     * <p>算法分为三个阶段：</p>
     * <ol>
     *   <li>每次读取 4 字节小端数据块，经过乘法和循环位移后混入 Hash。</li>
     *   <li>用相同规则处理末尾不足 4 字节的 1～3 个字节。</li>
     *   <li>混入输入长度并执行最终雪崩，使输入位的微小变化扩散到整个结果。</li>
     * </ol>
     * <p>实现中的混合常量属于 MurmurHash3 x86 32-bit 标准定义，不是业务参数，
     * 不得在当前 Hash 版本下调整。</p>
     *
     * @param input 输入字节
     * @param seed  固定种子
     * @return 有符号 32 位 Hash
     */
    private static int murmur3Hash32(byte[] input, int seed) {
        final int c1 = 0xcc9e2d51;
        final int c2 = 0x1b873593;

        int hash = seed;
        int blockEnd = input.length & 0xfffffffc;

        // 主体阶段：按 4 字节小端块进行混合。
        for (int index = 0; index < blockEnd; index += 4) {
            int block = (input[index] & 0xff)
                    | ((input[index + 1] & 0xff) << 8)
                    | ((input[index + 2] & 0xff) << 16)
                    | ((input[index + 3] & 0xff) << 24);

            block *= c1;
            block = Integer.rotateLeft(block, 15);
            block *= c2;

            hash ^= block;
            hash = Integer.rotateLeft(hash, 13);
            hash = hash * 5 + 0xe6546b64;
        }

        int tail = 0;
        int remaining = input.length & 3;

        // 尾部阶段：处理最后不足 4 字节的数据，避免任何输入字节被遗漏。
        if (remaining == 3) {
            tail ^= (input[blockEnd + 2] & 0xff) << 16;
        }
        if (remaining >= 2) {
            tail ^= (input[blockEnd + 1] & 0xff) << 8;
        }
        if (remaining >= 1) {
            tail ^= input[blockEnd] & 0xff;
            tail *= c1;
            tail = Integer.rotateLeft(tail, 15);
            tail *= c2;
            hash ^= tail;
        }

        // 终结阶段：混入长度并执行雪崩，增强输出位的扩散效果。
        hash ^= input.length;
        hash ^= hash >>> 16;
        hash *= 0x85ebca6b;
        hash ^= hash >>> 13;
        hash *= 0xc2b2ae35;
        hash ^= hash >>> 16;

        return hash;
    }
}
