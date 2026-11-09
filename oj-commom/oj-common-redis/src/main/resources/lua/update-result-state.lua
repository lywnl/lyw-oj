-- 同一个 Redis 命令内完成优先级判断，不能让等待或系统错误覆盖真实结果。
local priority = { WAITING = 1, SYSTEM_ERROR = 2, JUDGED = 3 }
local candidate = cjson.decode(ARGV[1])
local nextPriority = priority[candidate.cacheState]
if not nextPriority then
    return redis.error_reply('INVALID_RESULT_STATE')
end
local current = redis.call('GET', KEYS[1])
if current then
    local previous = cjson.decode(current)
    local currentPriority = priority[previous.cacheState]
    if not currentPriority then
        return redis.error_reply('INVALID_EXISTING_RESULT_STATE')
    end
    if currentPriority >= nextPriority then
        return 0
    end
end
-- 保存原始 JSON 字符串，避免 Lua cjson 对大整数 ID 重编码丢失精度。
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
return 1
