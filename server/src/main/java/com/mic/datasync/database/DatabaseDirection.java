package com.mic.datasync.database;

/**
 * 数据同步方向规则（MVP 冻结）。
 *
 * <p>支持：KingbaseES→openGauss、openGauss→openGauss、openGauss→KingbaseES；
 * 禁止：KingbaseES→KingbaseES（返回 UNSUPPORTED_DATABASE_DIRECTION）。</p>
 */
public final class DatabaseDirection {

    private DatabaseDirection() {
    }

    /**
     * 判断同步方向是否受支持。
     *
     * @param sourceType 源数据库类型
     * @param targetType 目标数据库类型
     */
    public static boolean isSupported(DatabaseType sourceType, DatabaseType targetType) {
        return !(sourceType == DatabaseType.KINGBASE_ES && targetType == DatabaseType.KINGBASE_ES);
    }
}
