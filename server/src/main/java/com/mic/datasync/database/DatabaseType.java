package com.mic.datasync.database;

/**
 * 支持的目标/源数据库类型（MVP 冻结为两种）。
 */
public enum DatabaseType {

    /** 人大金仓 KingbaseES。 */
    KINGBASE_ES("KingbaseES", "人大金仓 KingbaseES"),

    /** openGauss。 */
    OPEN_GAUSS("openGauss", "openGauss");

    private final String productName;
    private final String displayName;

    DatabaseType(String productName, String displayName) {
        this.productName = productName;
        this.displayName = displayName;
    }

    /** JDBC DatabaseMetaData 中的产品名（用于校验）。 */
    public String productName() {
        return productName;
    }

    /** 面向用户的展示名。 */
    public String displayName() {
        return displayName;
    }
}
