package com.lyw.system.questionsync;

import org.elasticsearch.client.RestClient;
import com.lyw.api.reconcile.QuestionReconcileProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties({SyncProperties.class, QuestionReconcileProperties.class})
public class QuestionSyncConfiguration {
    @Bean public CanalEventMapper canalEventMapper(SyncProperties properties) { return new CanalEventMapper(properties.getDatabase()); }
    @Bean public IndexStore questionIndexStore(RestClient client, SyncProperties properties) {
        return new ElasticsearchIndexStore(client, properties.getTimeoutMillis());
    }
    @Bean public QuestionReader questionSyncReader(DataSource dataSource, SyncProperties properties) {
        return new JdbcQuestionReader(dataSource, properties.getTimeoutMillis());
    }
    @Bean public PositionGuard questionPositionGuard(SyncProperties properties, DataSource dataSource) {
        return new SourcePositionGuard(properties, dataSource);
    }
    @Bean public QuestionReconciler questionReconciler(QuestionReader reader, IndexStore index, PositionGuard guard, SyncProperties properties) {
        return new QuestionReconciler(reader, index, guard, properties.getPageSize());
    }
    @Bean public QuestionEsSyncRunner questionEsSyncRunner(SyncProperties properties, CanalEventMapper mapper, IndexStore index,
            PositionGuard guard, QuestionReconciler reconciler, QuestionReconcileProperties reconcileProperties) {
        return new QuestionEsSyncRunner(properties, new TcpCanalSource(properties), mapper, index, guard, reconciler, reconcileProperties);
    }
}
