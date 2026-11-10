package com.lyw.system.questionsync;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

/** 同一 JAR 的一次性维护上下文，不实例化普通控制器、演示模块或 Repository。 */
@Configuration(proxyBeanMethods = false)
@Profile("question-maintenance")
@ImportAutoConfiguration({PropertyPlaceholderAutoConfiguration.class, ConfigurationPropertiesAutoConfiguration.class,
        DataSourceAutoConfiguration.class, ElasticsearchRestClientAutoConfiguration.class})
@Import({QuestionSyncConfiguration.class, QuestionSyncMaintenanceRunner.class})
public class QuestionSyncMaintenanceApplication {
}
