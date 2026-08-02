package com.mic.datasync.database.kingbase;

import com.mic.datasync.database.DatabaseType;
import com.mic.datasync.database.support.PostgresLikeTargetAdapter;

/**
 * KingbaseES Target（Writer）适配器。
 */
public class KingbaseTargetAdapter extends PostgresLikeTargetAdapter {

    @Override
    public DatabaseType databaseType() {
        return DatabaseType.KINGBASE_ES;
    }
}
