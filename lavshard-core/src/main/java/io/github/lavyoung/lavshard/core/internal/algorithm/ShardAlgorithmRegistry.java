package io.github.lavyoung.lavshard.core.internal.algorithm;

import io.github.lavyoung.lavshard.core.api.algorithm.ShardAlgorithm;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 分片算法注册表
 *
 * <p>注册表在构造阶段完成算法名称校验、重复检测和不可变快照创建。
 * 构造完成后不支持新增、替换或删除算法。
 *
 * <p>算法名称采用精确匹配，不进行裁剪、大小写转换或别名解析。
 *
 * @author <a href="mailto:lavyoung1325@outlook.com">lavyoung</a>
 * @version 0.1.0
 * @date 2026/09/10
 */
public final class ShardAlgorithmRegistry {

    private final Map<String, ShardAlgorithm> algorithms;

    /**
     * 使用给定算法集合构造不可变注册表。
     *
     * <p>构造过程复制算法映射，因此调用方后续修改原始集合不会影响注册结果。
     * 任意算法名称重复时立即失败，避免由集合顺序产生不透明的覆盖优先级。
     *
     * @param algorithms 待注册的算法集合
     * @throws NullPointerException     algorithms、算法元素或算法名称为 null 时抛出
     * @throws IllegalArgumentException 算法名称为空白或存在重复名称时抛出
     */
    public ShardAlgorithmRegistry(Collection<? extends ShardAlgorithm> algorithms) {
        Objects.requireNonNull(algorithms, "algorithms must not be null");

        Map<String, ShardAlgorithm> registeredAlgorithms = new HashMap<>();
        for (ShardAlgorithm algorithm : algorithms) {
            Objects.requireNonNull(algorithm, "algorithm must not be null");
            String name = requireValidName(algorithm.name());
            ShardAlgorithm existing = registeredAlgorithms.putIfAbsent(name, algorithm);
            if (existing != null) {
                throw new IllegalArgumentException(
                        "duplicate shard algorithm name: " + name
                );
            }
        }
        this.algorithms = Map.copyOf(registeredAlgorithms);
    }

    /**
     * 创建包含全部v0.1.0内置的算法注册表
     *
     * @return 包含内置Murmur3 Hash 算法的不可变注册表
     */
    public static ShardAlgorithmRegistry withBuiltInAlgorithms() {
        return new ShardAlgorithmRegistry(List.of(new Murmur3HashShardAlgorithm()));
    }

    /**
     * 按照名称精确查找算法
     *
     * @param name 算法名称
     * @return 找到的算法；合法名称未注册时返回 Optional.empty()
     * @throws NullPointerException     name 为 null 时抛出
     * @throws IllegalArgumentException name 为空白时抛出
     */
    public Optional<ShardAlgorithm> find(String name) {
        return Optional.ofNullable(algorithms.get(requireValidName(name)));
    }


    private static String requireValidName(String name) {
        Objects.requireNonNull(name, "algorithm name must not be null");

        if (name.isBlank()) {
            throw new IllegalArgumentException("algorithm name must not be blank");
        }
        return name;
    }
}
