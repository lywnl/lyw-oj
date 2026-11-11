package com.lyw.system.questionsync;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.nio.charset.StandardCharsets;

@Getter
@Setter
@ConfigurationProperties("canal.sync")
public class SyncProperties {
    public static final String INDEX = "idx_question";
    public static final String TABLE = "tb_question";
    private boolean enabled;
    private String mode = "normal";
    private String database;
    private String destination = "question-sync";
    private String host = "127.0.0.1";
    private int port = 11111;
    private String username = "";
    private String password = "";
    private int clientId = 1001;
    private int batchSize = 100;
    private long idleMillis = 1000;
    private int timeoutMillis = 5000;
    private long retryMaxMillis = 30000;
    private long positionCheckMillis = 30000;
    /** 仅检测旧配置；normal 模式必须移除并改由 XXL-JOB 调度。 */
    private Long reconcileIntervalMillis;
    private long reconcileWarningMillis = 300000;
    private int pageSize = 500;
    private String stateDirectory;
    private String initialFile;
    private long initialPosition;
    private String sourceJdbcUrl;
    private String sourceUsername;
    private String sourcePassword;
    private boolean maintenanceConfirmed;

    public void validate() {
        if ("normal".equals(mode) && reconcileIntervalMillis != null)
            throw new SyncBlockedException("请移除 canal.sync.reconcile-interval-millis（包括 0），改由 XXL-JOB 启停每日对账");
        if (database == null || !database.matches("[a-zA-Z0-9_-]+"))
            throw new SyncBlockedException("必须配置合法的业务库名 canal.sync.database");
        if (!destination.matches("[a-zA-Z0-9_-]+") || clientId != 1001)
            throw new SyncBlockedException("destination 必须合法，客户端身份固定为 1001");
        if (batchSize < 1 || batchSize > 1000 || pageSize < 1 || pageSize > 1000
                || timeoutMillis < 1 || idleMillis < 1 || retryMaxMillis < 1000
                || positionCheckMillis < 1 || (reconcileIntervalMillis != null && reconcileIntervalMillis < 0)
                || reconcileWarningMillis < 1
                || port < 1 || port > 65535)
            throw new SyncBlockedException("同步批次、分页或超时配置无效");
        if (stateDirectory == null || stateDirectory.isBlank() || sourceJdbcUrl == null
                || !sourceJdbcUrl.startsWith("jdbc:mysql:") || sourceUsername == null || sourcePassword == null)
            throw new SyncBlockedException("必须配置位点目录和复制源 JDBC 连接");
        if (sourceUsername.isBlank() || sourcePassword.isEmpty() || sourceUsername.getBytes(StandardCharsets.UTF_8).length > 32
                || sourcePassword.getBytes(StandardCharsets.UTF_8).length > 32)
            throw new SyncBlockedException("Canal 1.1.8 副本注册要求账号/密码为非空且不超过 32 个 UTF-8 字节");
    }
}
