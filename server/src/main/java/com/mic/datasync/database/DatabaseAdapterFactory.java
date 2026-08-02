package com.mic.datasync.database;

import com.mic.datasync.database.kingbase.KingbaseSourceAdapter;
import com.mic.datasync.database.kingbase.KingbaseTargetAdapter;
import com.mic.datasync.database.opengauss.OpenGaussSourceAdapter;
import com.mic.datasync.database.opengauss.OpenGaussTargetAdapter;
import org.springframework.stereotype.Component;

/**
 * 根据数据库类型创建对应的 Source/Target 适配器。
 */
@Component
public class DatabaseAdapterFactory {

    /** 创建 Source（Reader）适配器。 */
    public SourceDatabaseAdapter sourceAdapter(DatabaseType type) {
        return switch (type) {
            case KINGBASE_ES -> new KingbaseSourceAdapter();
            case OPEN_GAUSS -> new OpenGaussSourceAdapter();
        };
    }

    /** 创建 Target（Writer）适配器。 */
    public TargetDatabaseAdapter targetAdapter(DatabaseType type) {
        return switch (type) {
            case KINGBASE_ES -> new KingbaseTargetAdapter();
            case OPEN_GAUSS -> new OpenGaussTargetAdapter();
        };
    }
}
