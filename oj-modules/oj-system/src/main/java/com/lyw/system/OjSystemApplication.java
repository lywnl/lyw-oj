package com.lyw.system;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.lyw.system.questionsync.QuestionSyncMaintenanceApplication;
import java.util.Arrays;

@SpringBootApplication
public class OjSystemApplication {
    public static void main(String[] args) {
        boolean maintenance = Arrays.stream(args).anyMatch(argument -> "--canal.sync.mode=check".equals(argument)
                || "--canal.sync.mode=repair".equals(argument));
        if (maintenance) {
            SpringApplication application = new SpringApplication(QuestionSyncMaintenanceApplication.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setAdditionalProfiles("question-maintenance");
            application.run(args);
        } else SpringApplication.run(OjSystemApplication.class, args);
    }

}
