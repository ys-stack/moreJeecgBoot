package org.jeecg.modules.airag.practice.aspect;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.SecurityUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.jeecg.common.exception.JeecgBootException;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.common.util.IpUtils;
import org.jeecg.common.util.SpringContextUtils;
import org.jeecg.modules.airag.practice.aspect.annotation.RateLimit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Collections;

/**
 * 限流切面 —— Redis 固定窗口 + Lua 原子操作
 * Redis 不可用时 fail-open 降级放行
 */
@Slf4j
@Aspect
@Component
public class RateLimitAspect {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** Lua: INCR + 首次 EXPIRE，保证原子性 */
    private static final String LUA_SCRIPT =
            "local count = redis.call('INCR', KEYS[1])\n" +
            "if count == 1 then\n" +
            "    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))\n" +
            "end\n" +
            "return count";

    private static final DefaultRedisScript<Long> REDIS_SCRIPT;

    static {
        REDIS_SCRIPT = new DefaultRedisScript<>();
        REDIS_SCRIPT.setScriptText(LUA_SCRIPT);
        REDIS_SCRIPT.setResultType(Long.class);
    }

    @Around("@annotation(org.jeecg.modules.airag.practice.aspect.annotation.RateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        long periodSeconds = rateLimit.timeUnit().toSeconds(rateLimit.time());
        String key = buildKey(pjp, rateLimit, periodSeconds);
        log.info("限流检查: key={}, limit={}/{}", key, rateLimit.count(), rateLimit.time() + rateLimit.timeUnit().name().toLowerCase());

        try {
            Long count = stringRedisTemplate.execute(
                    REDIS_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(periodSeconds)
            );

            if (count != null && count > rateLimit.count()) {
                log.warn("限流触发: key={}, count={}/{}", key, count, rateLimit.count());
                throw new JeecgBootException(rateLimit.message());
            }
        } catch (JeecgBootException e) {
            throw e;
        } catch (Exception e) {
            log.error("限流检查失败（Redis异常），降级放行: {}", e.getMessage());
        }

        return pjp.proceed();
    }

    /**
     * 构建 key: rate_limit:{接口标识}:{维度标识}:{窗口序号}
     */
    private String buildKey(ProceedingJoinPoint pjp, RateLimit rateLimit, long periodSeconds) {
        String apiId = rateLimit.key().isEmpty()
                ? resolveApiId(pjp)
                : rateLimit.key();

        String clientId = resolveClientId(rateLimit.dimension());

        long windowIndex = System.currentTimeMillis() / 1000 / periodSeconds;

        return "rate_limit:" + apiId + ":" + clientId + ":" + windowIndex;
    }

    private String resolveApiId(ProceedingJoinPoint pjp) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        return sig.getDeclaringType().getSimpleName() + ":" + sig.getName();
    }

    /**
     * 按维度解析客户端标识
     */
    private String resolveClientId(RateLimit.Dimension dimension) {
        return switch (dimension) {
            case USER -> resolveUserId();
            case IP -> resolveIp();
            case GLOBAL -> "global";
            case AUTO -> {
                String uid = resolveUserId();
                yield uid != null ? uid : resolveIp();
            }
        };
    }

    private String resolveUserId() {
        try {
            Object principal = SecurityUtils.getSubject().getPrincipal();
            if (principal instanceof LoginUser loginUser) {
                return "u:" + loginUser.getUsername();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String resolveIp() {
        try {
            HttpServletRequest request = SpringContextUtils.getHttpServletRequest();
            return "ip:" + IpUtils.getIpAddr(request);
        } catch (Exception e) {
            return "ip:unknown";
        }
    }
}
