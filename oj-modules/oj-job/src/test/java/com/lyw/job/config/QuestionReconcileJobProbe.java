package com.lyw.job.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import java.nio.file.Files;
import java.nio.file.Path;

/** 仅供隔离 Admin 验收使用，启动真实执行器和生产 Handler，不扫描其他业务模块。 */
public final class QuestionReconcileJobProbe {
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({FeignAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class})
    @Import({XxlJobConfig.class, QuestionReconcileJobConfiguration.class})
    static class ProbeConfiguration {
        @Bean static BeanPostProcessor isolatedExecutorSettings() {
            return new BeanPostProcessor() {
                @Override public Object postProcessBeforeInitialization(Object bean, String name) {
                    if (bean instanceof XxlJobSpringExecutor executor) {
                        executor.setAddress("http://" + System.getProperty("probe.runtime-host") + ":9999");
                        executor.setPort(9999);
                        executor.setLogPath(System.getProperty("probe.directory") + "/xxl-logs");
                        executor.setLogRetentionDays(1);
                    }
                    return bean;
                }
            };
        }
    }

    public static void main(String[] args) throws Exception {
        Path folder = Path.of(System.getProperty("probe.directory")); Files.createDirectories(folder);
        try (var context = new AnnotationConfigApplicationContext(ProbeConfiguration.class)) {
            Files.writeString(folder.resolve("job-ready"), "真实 XxlJobSpringExecutor 已启动");
            long end = System.nanoTime() + 600_000_000_000L;
            while (!Files.exists(folder.resolve("stop")) && System.nanoTime() < end) Thread.sleep(100);
        }
    }
}
