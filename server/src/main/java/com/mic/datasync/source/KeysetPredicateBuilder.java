package com.mic.datasync.source;

import com.mic.datasync.database.dialect.SourceDialect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Keyset（游标）分页谓词生成器。
 *
 * <p>组合键分页使用「前缀相等 + 末位大于」的 OR 链，保证同值多行（如同时间戳）
 * 也能稳定推进，不会漏行或重复：</p>
 * <pre>
 * (k1 &gt; ?)
 * OR (k1 = ? AND k2 &gt; ?)
 * OR (k1 = ? AND k2 = ? AND k3 &gt; ?)
 * </pre>
 *
 * <p>分页键值不允许 NULL：任一游标值为 null 时抛出异常（NULL 拒绝）。</p>
 */
public final class KeysetPredicateBuilder {

    private KeysetPredicateBuilder() {
    }

    /**
     * 生成 Keyset 谓词（不含 WHERE 关键字）。
     *
     * @param keys         分页键（顺序即比较顺序）
     * @param cursorValues 上一批最后一行各键值；null/空表示首批（无谓词）
     * @param dialect      方言（标识符引用）
     * @return 谓词与参数列表；首批返回 null 表示无需过滤
     */
    public static KeysetPredicate buildPredicate(
            List<String> keys,
            Map<String, Object> cursorValues,
            SourceDialect dialect) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("分页键不能为空");
        }
        if (cursorValues == null || cursorValues.isEmpty()) {
            return null;
        }
        // NULL 拒绝：所有分页键的游标值必须存在且非 null
        for (String key : keys) {
            if (!cursorValues.containsKey(key) || cursorValues.get(key) == null) {
                throw new IllegalArgumentException("分页键不允许 NULL: " + key);
            }
        }

        List<String> orClauses = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            List<String> equalParts = new ArrayList<>();
            for (int j = 0; j < i; j++) {
                equalParts.add(dialect.quoteIdentifier(keys.get(j)) + " = ?");
                parameters.add(cursorValues.get(keys.get(j)));
            }
            equalParts.add(dialect.quoteIdentifier(keys.get(i)) + " > ?");
            parameters.add(cursorValues.get(keys.get(i)));
            orClauses.add(String.join(" AND ", equalParts));
        }
        // 单 Key 不带括号；组合 Key 用括号包裹每个 OR 分支
        if (orClauses.size() == 1) {
            return new KeysetPredicate(orClauses.get(0), parameters);
        }
        return new KeysetPredicate("(" + String.join(") OR (", orClauses) + ")", parameters);
    }

    /** Keyset 谓词与绑定参数。 */
    public record KeysetPredicate(String sql, List<Object> parameters) {

        public KeysetPredicate {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
        }
    }
}
