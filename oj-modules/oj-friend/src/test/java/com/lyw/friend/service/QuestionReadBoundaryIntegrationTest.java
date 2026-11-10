package com.lyw.friend.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.lyw.api.domain.dto.JudgeSubmitDTO;
import com.lyw.common.core.constants.Constants;
import com.lyw.common.core.utils.ThreadLocalUtil;
import com.lyw.friend.domain.user.dto.UserQuestionSubmitDTO;
import com.lyw.friend.elasticsearch.QuestionRepository;
import com.lyw.friend.mapper.question.QuestionMapper;
import com.lyw.friend.service.question.impl.QuestionServiceImpl;
import com.lyw.friend.service.user.impl.UserQuestionServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 脚本停掉真实 ES 后，以真实 MySQL 和生产 Mapper 验证读路径。 */
@EnabledIfSystemProperty(named = "canal.business.integration", matches = "true")
class QuestionReadBoundaryIntegrationTest {
    @Test void detailsAndJudgeSnapshotRemainAvailableWithElasticsearchStopped() throws Exception {
        Properties config = new Properties();
        try (var input = Files.newInputStream(Path.of(System.getProperty("canal.integration.directory"), "connection.properties"))) { config.load(input); }
        assertEquals("lywoj_it", config.getProperty("database"), "只能连接本轮隔离库");
        var source = new DriverManagerDataSource(config.getProperty("jdbc.url"), "app_sync", config.getProperty("app.password"));
        MybatisConfiguration configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean(); factory.setConfiguration(configuration); factory.setDataSource(source);
        GlobalConfig global = new GlobalConfig(); global.setDbConfig(new GlobalConfig.DbConfig()); factory.setGlobalConfig(global);
        var sessions = factory.getObject(); sessions.getConfiguration().addMapper(QuestionMapper.class);
        QuestionMapper mapper = new SqlSessionTemplate(sessions).getMapper(QuestionMapper.class);
        var repository = mock(QuestionRepository.class);
        var detail = new QuestionServiceImpl(); ReflectionTestUtils.setField(detail, "questionMapper", mapper);
        ReflectionTestUtils.setField(detail, "questionRepository", repository);
        assertEquals("ES恢复后补上", detail.detail(1L).getTitle()); verifyNoInteractions(repository);
        var service = new UserQuestionServiceImpl(); ReflectionTestUtils.setField(service, "questionMapper", mapper);
        UserQuestionSubmitDTO request = new UserQuestionSubmitDTO(); request.setQuestionId(1L); request.setProgramType(0); request.setUserCode("public class Solution {}");
        ThreadLocalUtil.set(Constants.USER_ID, 7L);
        try {
            JudgeSubmitDTO result = ReflectionTestUtils.invokeMethod(service, "assembleJudgeSubmitDTO", request);
            assertNotNull(result); assertEquals(2345L, result.getTimeLimit()); assertEquals(1024000L, result.getSpaceLimit());
            assertEquals(List.of("7 8"), result.getInputList()); assertEquals(List.of("15"), result.getOutputList());
            assertTrue(result.getUserCode().contains("最新入口")); assertEquals(7L, result.getUserId());
        } finally { ThreadLocalUtil.remove(); }
    }
}
