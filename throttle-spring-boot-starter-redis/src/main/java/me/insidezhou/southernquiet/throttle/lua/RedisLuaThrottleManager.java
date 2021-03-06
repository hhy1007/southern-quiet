package me.insidezhou.southernquiet.throttle.lua;

import me.insidezhou.southernquiet.throttle.BaseThrottleManager;
import me.insidezhou.southernquiet.throttle.Throttle;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisLuaThrottleManager extends BaseThrottleManager {

    private final StringRedisTemplate stringRedisTemplate;

    public RedisLuaThrottleManager(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Throttle createTimeBased(String throttleName, long countDelay) {
        return new RedisLuaTimeBasedThrottle(stringRedisTemplate, throttleName, countDelay);
    }

    @Override
    public Throttle createCountBased(String throttleName) {
        return new RedisLuaCountBasedThrottle(stringRedisTemplate, throttleName);
    }
}
