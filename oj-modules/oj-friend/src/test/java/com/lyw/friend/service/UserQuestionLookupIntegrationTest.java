package com.lyw.friend.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.lyw.api.domain.enums.JudgeResultState;
import com.lyw.common.core.constants.Constants;
import com.lyw.common.core.utils.ThreadLocalUtil;
import com.lyw.common.redis.service.StateCacheService;
import com.lyw.friend.mapper.user.UserSubmitMapper;
import com.lyw.friend.service.user.impl.UserQuestionServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 用真实 MySQL 和生产查询 XML 验证归属过滤，Redis 在此测试中模拟未命中。 */
@EnabledIfSystemProperty(named = "oj.integration", matches = "true")
class UserQuestionLookupIntegrationTest {
    @AfterEach
    void clearUser() { ThreadLocalUtil.remove(); }

    @Test
    void productionQueryXmlReturnsOnlyTheAuthenticatedUsersSubmission() throws Exception {
        String url = System.getProperty("oj.mysql.url", "jdbc:mysql://mysql:3306/oj_idempotency?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        assertTrue(url.contains("/oj_idempotency?"), "拒绝在非隔离测试库运行");
        DriverManagerDataSource source = new DriverManagerDataSource(url, "oj_test", "oj_test_password");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS tb_user_submit (
                  submit_id BIGINT UNSIGNED PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL,
                  question_id BIGINT UNSIGNED NOT NULL, exam_id BIGINT UNSIGNED NULL,
                  program_type TINYINT NOT NULL, user_code TEXT NOT NULL, pass TINYINT NOT NULL,
                  exe_message VARCHAR(500) NOT NULL, case_judge_res TEXT,
                  score INT NOT NULL DEFAULT 0, create_by BIGINT UNSIGNED NOT NULL,
                  create_time DATETIME NOT NULL, update_by BIGINT UNSIGNED, update_time DATETIME)
                """);
        long owned = IdWorker.getId(), foreign = IdWorker.getId();
        String insert = "INSERT INTO tb_user_submit (submit_id,user_id,question_id,program_type,user_code,pass,exe_message,case_judge_res,score,create_by,create_time) VALUES (?,?,9,0,'test code',1,?,'[]',10,?,NOW())";
        jdbc.update(insert, owned, 7L, "本人结果", 7L);
        jdbc.update(insert, foreign, 8L, "其他用户结果", 8L);
        try {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setConfiguration(configuration);
            factory.setDataSource(source);
            GlobalConfig global = new GlobalConfig();
            global.setDbConfig(new GlobalConfig.DbConfig());
            factory.setGlobalConfig(global);
            factory.setMapperLocations(new ClassPathResource("mapper/user/UserSubmitMapper.xml"));
            UserSubmitMapper mapper = new SqlSessionTemplate(factory.getObject()).getMapper(UserSubmitMapper.class);
            UserQuestionServiceImpl service = new UserQuestionServiceImpl();
            ReflectionTestUtils.setField(service, "userSubmitMapper", mapper);
            ReflectionTestUtils.setField(service, "stateCacheService", mock(StateCacheService.class));
            ThreadLocalUtil.set(Constants.USER_ID, 7L);
            assertEquals(JudgeResultState.NO_RESULT, service.getExeResult(foreign, true).getResultState());
            var result = service.getExeResult(owned, true);
            assertEquals(JudgeResultState.JUDGED, result.getResultState());
            assertEquals("本人结果", result.getExeMessage());
            assertEquals(owned, result.getSubmitId());
            assertNull(mapper.selectUserSubmit(8L, owned));
        } finally {
            jdbc.update("DELETE FROM tb_user_submit WHERE submit_id IN (?,?)", owned, foreign);
        }
    }
}
