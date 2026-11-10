package com.lyw.system.questionsync;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SyncConfigurationTest {
    @Test void maintenanceBootstrapDoesNotParticipateInOrdinaryComponentRegistration() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(QuestionSyncMaintenanceApplication.class); context.refresh();
            assertEquals(0, context.getBeansOfType(QuestionEsSyncRunner.class).size());
            assertEquals(0, context.getBeansOfType(QuestionSyncMaintenanceRunner.class).size());
        }
    }
    @Test void deploymentEnvironmentVariablesBindWithoutLosingWordBoundaries() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("deployment", Map.of(
                "CANAL_SYNC_DATABASE", "lywoj", "CANAL_SYNC_STATE_DIRECTORY", "/state",
                "CANAL_SYNC_INITIAL_FILE", "mysql-bin.000001", "CANAL_SYNC_INITIAL_POSITION", "157",
                "CANAL_SYNC_SOURCE_JDBC_URL", "jdbc:mysql://mysql:3306/lywoj",
                "CANAL_SYNC_SOURCE_USERNAME", "canal_sync", "CANAL_SYNC_SOURCE_PASSWORD", "isolated-test")));
        SyncProperties p = Binder.get(environment).bind("canal.sync", SyncProperties.class).get();
        assertEquals("/state", p.getStateDirectory()); assertEquals("mysql-bin.000001", p.getInitialFile());
        assertEquals(157, p.getInitialPosition()); assertEquals("jdbc:mysql://mysql:3306/lywoj", p.getSourceJdbcUrl());
        assertEquals("canal_sync", p.getSourceUsername()); assertEquals("isolated-test", p.getSourcePassword());
        assertFalse(p.isEnabled()); p.validate();
    }
}
