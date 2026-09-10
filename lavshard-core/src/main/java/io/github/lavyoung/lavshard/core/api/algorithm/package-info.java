/**
 * 分片算法公开契约。
 *
 * <p>算法负责将类型明确的分片键映射到固定逻辑桶，
 * 不负责选择数据库、节点或物理表。
 */
package io.github.lavyoung.lavshard.core.api.algorithm;