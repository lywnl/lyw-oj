package com.lyw.system.questionsync;

import com.alibaba.fastjson2.JSON;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;

/** 以同一应用的一次性非 Web 模式运行，不增加远程运维接口。 */
@Component
public class QuestionSyncMaintenanceRunner implements ApplicationRunner {
    private final SyncProperties properties;
    private final PositionGuard positions;
    private final IndexStore index;
    private final QuestionReconciler reconciler;
    private final ConfigurableApplicationContext context;

    public QuestionSyncMaintenanceRunner(SyncProperties properties, PositionGuard positions, IndexStore index,
            QuestionReconciler reconciler, ConfigurableApplicationContext context) {
        this.properties = properties; this.positions = positions; this.index = index; this.reconciler = reconciler;
        this.context = context;
    }
    @Override public void run(ApplicationArguments args) {
        if ("normal".equals(properties.getMode())) return;
        int result;
        try { result = execute(); }
        catch (Exception failure) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("mode", properties.getMode()); report.put("status", "FAILED");
            report.put("reason", failure instanceof SyncBlockedException ? failure.getMessage() : failure.getClass().getSimpleName());
            System.out.println(JSON.toJSONString(report)); result = 1;
        }
        int code = result;
        System.exit(SpringApplication.exit(context, () -> code));
    }
    int execute() throws Exception {
        properties.validate();
        if (properties.isEnabled() || !("check".equals(properties.getMode()) || "repair".equals(properties.getMode())))
            throw new SyncBlockedException("维护模式仅支持 check/repair，且必须关闭增量同步");
        boolean repair = "repair".equals(properties.getMode());
        if (repair && !properties.isMaintenanceConfirmed())
            throw new SyncBlockedException("repair 需要明确确认旧 writer/外部题目写入已停止");
        BinlogPosition saved = null;
        boolean expired = false;
        try { saved = positions.check(); }
        catch (ExpiredPositionException invalid) {
            if (!repair) throw invalid;
            expired = true;
        }
        // check 验证 TCP 可达性；repair 可在 Canal 停机维护时执行，不依赖消费服务在线。
        if (!repair) try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(properties.getHost(), properties.getPort()), properties.getTimeoutMillis());
        }
        boolean existed = index.exists();
        if (!existed && !repair) throw new SyncBlockedException("索引不存在，需要显式 repair 初始化");
        if (!existed) index.create();
        index.validate(); reconciler.reset();
        try {
            while (!reconciler.finished()) reconciler.step(null, System.currentTimeMillis(), repair, false);
        } finally { reconciler.close(); }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("mode", properties.getMode()); report.put("database", properties.getDatabase());
        report.put("position", saved); report.put("currentSourcePosition", positions.capture());
        report.put("positionResetRequired", expired);
        report.put("indexPreserved", existed); report.put("differences", reconciler.differences());
        report.put("repaired", reconciler.repaired()); report.put("status", "COMPLETE");
        System.out.println(JSON.toJSONString(report));
        return !repair && reconciler.differences() > 0 ? 2 : 0;
    }
}
