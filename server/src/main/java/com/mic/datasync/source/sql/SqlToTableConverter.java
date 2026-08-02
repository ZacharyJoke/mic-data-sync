package com.mic.datasync.source.sql;

import com.mic.datasync.source.domain.FilterCondition;
import com.mic.datasync.source.domain.TableReadDefinition;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQL→Table 尽力转换器。
 *
 * <p>仅当表、字段、WHERE、ORDER BY 全部可可靠还原时返回转换结果；
 * 任何无法可靠还原的结构（如 *、函数、JOIN、复杂条件）返回空，
 * 不阻止合法 SQL 模式继续使用。</p>
 */
@Component
public class SqlToTableConverter {

    /**
     * 尝试将单表 SQL 转换为 Table 读取定义。
     *
     * @return 转换成功返回定义（paginationKeys 取 ORDER BY 列，用户可调整）；
     *         不可靠还原返回 {@link Optional#empty()}
     */
    public Optional<TableReadDefinition> tryConvert(String sql) {
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (Exception ex) {
            return Optional.empty();
        }
        if (!(statement instanceof Select select)
                || !(select.getSelectBody() instanceof PlainSelect plainSelect)) {
            return Optional.empty();
        }
        if (!(plainSelect.getFromItem() instanceof Table table)
                || plainSelect.getJoins() != null && !plainSelect.getJoins().isEmpty()) {
            return Optional.empty();
        }
        String schema = table.getSchemaName();
        String tableName = table.getName();
        if (tableName == null || tableName.isBlank()) {
            return Optional.empty();
        }

        // 字段：每个 SELECT 项必须是裸列（Column）或带别名，且可还原
        List<String> selectedColumns = new ArrayList<>();
        for (SelectItem<?> item : plainSelect.getSelectItems()) {
            Expression expression = item.getExpression();
            if (!(expression instanceof Column column)) {
                return Optional.empty();
            }
            String name = item.getAlias() != null
                    ? item.getAlias().getName()
                    : column.getColumnName();
            if (name == null || name.isBlank()) {
                return Optional.empty();
            }
            selectedColumns.add(name);
        }
        if (selectedColumns.isEmpty()) {
            return Optional.empty();
        }

        // WHERE：只支持 AND 连接的简单比较，值必须是字面量
        List<FilterCondition> filters = new ArrayList<>();
        if (plainSelect.getWhere() != null
                && !extractFilters(plainSelect.getWhere(), filters)) {
            return Optional.empty();
        }

        // ORDER BY：只支持裸列（还原为分页键候选）
        List<String> orderByColumns = new ArrayList<>();
        if (plainSelect.getOrderByElements() != null) {
            for (OrderByElement element : plainSelect.getOrderByElements()) {
                if (!(element.getExpression() instanceof Column column)) {
                    return Optional.empty();
                }
                orderByColumns.add(column.getColumnName());
            }
        }

        return Optional.of(new TableReadDefinition(
                schema, tableName, selectedColumns, filters, orderByColumns, null));
    }

    /** 递归拆分 AND 条件为简单比较；无法还原时返回 false。 */
    private boolean extractFilters(Expression expression, List<FilterCondition> target) {
        if (expression instanceof AndExpression andExpression) {
            return extractFilters(andExpression.getLeftExpression(), target)
                    && extractFilters(andExpression.getRightExpression(), target);
        }
        FilterCondition condition = toFilterCondition(expression);
        if (condition == null) {
            return false;
        }
        target.add(condition);
        return true;
    }

    /** 简单比较（列 运算符 字面量）→ 过滤条件；其他返回 null。 */
    private FilterCondition toFilterCondition(Expression expression) {
        if (expression instanceof EqualsTo binary) {
            return simpleCondition(binary.getLeftExpression(), "=", binary.getRightExpression());
        }
        if (expression instanceof NotEqualsTo binary) {
            return simpleCondition(binary.getLeftExpression(), "!=", binary.getRightExpression());
        }
        if (expression instanceof GreaterThan binary) {
            return simpleCondition(binary.getLeftExpression(), ">", binary.getRightExpression());
        }
        if (expression instanceof GreaterThanEquals binary) {
            return simpleCondition(binary.getLeftExpression(), ">=", binary.getRightExpression());
        }
        if (expression instanceof MinorThan binary) {
            return simpleCondition(binary.getLeftExpression(), "<", binary.getRightExpression());
        }
        if (expression instanceof MinorThanEquals binary) {
            return simpleCondition(binary.getLeftExpression(), "<=", binary.getRightExpression());
        }
        return null;
    }

    private FilterCondition simpleCondition(Expression left, String operator, Expression right) {
        if (!(left instanceof Column column)) {
            return null;
        }
        Object value = literalValue(right);
        if (value == null && !(right instanceof net.sf.jsqlparser.expression.NullValue)) {
            return null;
        }
        if (right instanceof net.sf.jsqlparser.expression.NullValue) {
            return new FilterCondition(column.getColumnName(), operator, null);
        }
        return new FilterCondition(column.getColumnName(), operator, value);
    }

    private Object literalValue(Expression expression) {
        if (expression instanceof StringValue stringValue) {
            return stringValue.getValue();
        }
        if (expression instanceof LongValue longValue) {
            return longValue.getValue();
        }
        if (expression instanceof net.sf.jsqlparser.expression.DoubleValue doubleValue) {
            return doubleValue.getValue();
        }
        return null;
    }
}
