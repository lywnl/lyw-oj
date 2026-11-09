package com.lyw.common.redis.service;

import com.alibaba.fastjson2.JSON;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** 原子更新展示状态，不提供任务执行锁或数据库幂等语义。 */
public class StateCacheService {
    private static final DefaultRedisScript<Long> UPDATE = new DefaultRedisScript<>();
    static {
        UPDATE.setLocation(new ClassPathResource("lua/update-result-state.lua"));
        UPDATE.setResultType(Long.class);
    }

    private final StringRedisTemplate redis;

    public StateCacheService(RedisConnectionFactory factory) {
        this.redis = new StringRedisTemplate(factory);
    }

    public <T> T get(String key, Class<T> type) {
        String value = redis.opsForValue().get(key);
        if (value == null) return null;
        T result = JSON.parseObject(value, type);
        if (result == null) throw new IllegalStateException("结果缓存内容为空");
        return result;
    }

    public boolean update(String key, Object value, long timeout, TimeUnit unit) {
        long seconds = unit.toSeconds(timeout);
        if (seconds <= 0) throw new IllegalArgumentException("缓存有效期必须大于零");
        // 参数必须按普通字符串传递；JSON 模板会对 JSON 文本和 TTL 再编码一次。
        Long changed = redis.execute(UPDATE, List.of(key), JSON.toJSONString(value), Long.toString(seconds));
        if (changed == null) throw new IllegalStateException("结果缓存更新没有确认返回值");
        return changed == 1;
    }
}
