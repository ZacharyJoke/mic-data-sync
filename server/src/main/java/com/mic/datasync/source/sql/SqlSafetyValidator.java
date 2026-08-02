package com.mic.datasync.source.sql;

import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.HexValue;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.TimeValue;
import net.sf.jsqlparser.expression.TimestampValue;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * 单表只读 SQL 安全校验器。
 *
 * <p>接受：单条 {@link PlainSelect}、单基表、SELECT *、别名、CASE、CAST 与
 * 白名单无副作用表达式。拒绝：JOIN、集合操作、CTE、FROM 子查询、LIMIT/OFFSET/
 * FETCH/INTO、锁语句、DML/DDL 与副作用函数。</p>
 */
@Component
public class SqlSafetyValidator {

    /** 允许的无副作用函数白名单。 */
    private static final Set<String> PURE_FUNCTIONS = Set.of(
            "coalesce", "concat", "lower", "upper", "length", "substr", "substring",
            "trim", "abs", "round", "ceil", "floor", "nullif");

    /** 校验结果。valid=false 时 errorCode 与 message 供 UI 展示（可保存草稿但不可启用）。 */
    public record ValidationResult(boolean valid, String errorCode, String message) {

        public static ValidationResult ok() {
            return new ValidationResult(true, null, null);
        }

        public static ValidationResult invalid(String errorCode, String message) {
            return new ValidationResult(false, errorCode, message);
        }
    }

    /** 校验 SQL 是否安全可用。valid=false 时只能保存草稿，不能启用或执行。 */
    public ValidationResult validate(String sql) {
        if (sql == null || sql.isBlank()) {
            return ValidationResult.invalid("VALIDATION_FAILED", "SQL 不能为空");
        }
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (Exception ex) {
            return ValidationResult.invalid("VALIDATION_FAILED",
                    "SQL 无法解析，可保存为草稿但不能启用: " + safeMessage(ex));
        }
        if (!(statement instanceof Select select)) {
            return ValidationResult.invalid("SQL_NOT_SINGLE_SELECT", "仅支持单条只读 SELECT");
        }
        if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
            return ValidationResult.invalid("SQL_UNSUPPORTED_STRUCTURE", "不支持 CTE（WITH）");
        }
        if (!(select.getSelectBody() instanceof PlainSelect plainSelect)) {
            return ValidationResult.invalid("SQL_NOT_SINGLE_SELECT", "不支持 UNION/集合操作，仅支持单条 SELECT");
        }
        return validatePlainSelect(select, plainSelect);
    }

    private ValidationResult validatePlainSelect(Select select, PlainSelect plainSelect) {
        // FROM 单基表：无 JOIN、无子查询
        if (plainSelect.getFromItem() == null) {
            return ValidationResult.invalid("SQL_UNSUPPORTED_STRUCTURE", "缺少 FROM 子句");
        }
        if (!(plainSelect.getFromItem() instanceof Table)) {
            return ValidationResult.invalid("SQL_UNSUPPORTED_STRUCTURE", "FROM 必须是单张基表（不支持子查询）");
        }
        if (plainSelect.getJoins() != null && !plainSelect.getJoins().isEmpty()) {
            return ValidationResult.invalid("SQL_UNSUPPORTED_STRUCTURE", "不支持多表 JOIN");
        }
        if (select.getLimit() != null || select.getOffset() != null || select.getFetch() != null) {
            return ValidationResult.invalid("SQL_UNSUPPORTED_STRUCTURE", "不允许 LIMIT/OFFSET/FETCH");
        }
        if (plainSelect.getForMode() != null || plainSelect.getForUpdateTable() != null
                || plainSelect.getOracleHierarchical() != null
                || (plainSelect.getIntoTables() != null && !plainSelect.getIntoTables().isEmpty())) {
            return ValidationResult.invalid("SQL_UNSUPPORTED_STRUCTURE", "不允许锁语句或 INTO");
        }

        // SELECT 项：表达式白名单
        for (SelectItem<?> item : plainSelect.getSelectItems()) {
            String error = validateExpression(item.getExpression());
            if (error != null) {
                return ValidationResult.invalid("SQL_UNSUPPORTED_STRUCTURE", error);
            }
        }
        if (plainSelect.getWhere() != null) {
            String error = validateExpression(plainSelect.getWhere());
            if (error != null) {
                return ValidationResult.invalid("SQL_UNSUPPORTED_STRUCTURE", error);
            }
        }
        if (plainSelect.getOrderByElements() != null) {
            for (var orderBy : plainSelect.getOrderByElements()) {
                String error = validateExpression(orderBy.getExpression());
                if (error != null) {
                    return ValidationResult.invalid("SQL_UNSUPPORTED_STRUCTURE", error);
                }
            }
        }
        return ValidationResult.ok();
    }

    /** 校验表达式树；返回 null 表示合法，否则返回错误描述。 */
    private String validateExpression(Expression expression) {
        if (expression == null) {
            return null;
        }
        if (expression instanceof AllColumns || expression instanceof AllTableColumns) {
            return null; // SELECT * / 表.*
        }
        if (expression instanceof Column || isLiteral(expression)) {
            return null;
        }
        if (expression instanceof Function function) {
            String name = function.getName().toLowerCase(Locale.ROOT);
            if (!PURE_FUNCTIONS.contains(name)) {
                return "不支持的函数: " + function.getName();
            }
            if (function.getParameters() != null) {
                return validateExpressionList(function.getParameters());
            }
            return null;
        }
        if (expression instanceof CaseExpression caseExpression) {
            String error = validateExpression(caseExpression.getSwitchExpression());
            if (error != null) {
                return error;
            }
            for (var when : caseExpression.getWhenClauses()) {
                error = validateExpression(when.getWhenExpression());
                if (error != null) {
                    return error;
                }
                error = validateExpression(when.getThenExpression());
                if (error != null) {
                    return error;
                }
            }
            return validateExpression(caseExpression.getElseExpression());
        }
        if (expression instanceof CastExpression castExpression) {
            return validateExpression(castExpression.getLeftExpression());
        }
        if (expression instanceof Parenthesis parenthesis) {
            return validateExpression(parenthesis.getExpression());
        }
        if (expression instanceof NotExpression notExpression) {
            return validateExpression(notExpression.getExpression());
        }
        if (expression instanceof IsNullExpression isNullExpression) {
            return validateExpression(isNullExpression.getLeftExpression());
        }
        if (expression instanceof Between between) {
            String error = validateExpression(between.getLeftExpression());
            if (error != null) {
                return error;
            }
            error = validateExpression(between.getBetweenExpressionStart());
            return error != null ? error : validateExpression(between.getBetweenExpressionEnd());
        }
        if (expression instanceof InExpression inExpression) {
            String error = validateExpression(inExpression.getLeftExpression());
            if (error != null) {
                return error;
            }
            Expression items = inExpression.getRightExpression();
            if (items instanceof ExpressionList<?> expressionList) {
                return validateExpressionList(expressionList);
            }
            return "不支持 IN 子查询";
        }
        if (expression instanceof BinaryExpression binaryExpression) {
            String error = validateExpression(binaryExpression.getLeftExpression());
            if (error != null) {
                return error;
            }
            return validateExpression(binaryExpression.getRightExpression());
        }
        return "不支持的表达式: " + expression.getClass().getSimpleName();
    }

    private String validateExpressionList(ExpressionList<?> list) {
        for (Expression expression : list.getExpressions()) {
            String error = validateExpression(expression);
            if (error != null) {
                return error;
            }
        }
        return null;
    }

    private boolean isLiteral(Expression expression) {
        return expression instanceof LongValue
                || expression instanceof DoubleValue
                || expression instanceof StringValue
                || expression instanceof TimeValue
                || expression instanceof TimestampValue
                || expression instanceof NullValue
                || expression instanceof JdbcParameter
                || expression instanceof HexValue
                || expression instanceof SignedExpression;
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null ? ex.getClass().getSimpleName() : message;
    }
}
