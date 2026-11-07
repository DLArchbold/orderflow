package com.orderflow.order.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Configuration;

/** PLAN step 17c: a cache is an optimisation, never a requirement; if Redis is down, log and carry on without it. */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {
    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("Cache get failed ({} {}): {}", cache.getName(), key, e.toString());
            }
            @Override public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("Cache put failed ({} {}): {}", cache.getName(), key, e.toString());
            }
            @Override public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.warn("Cache evict failed ({} {}): {}", cache.getName(), key, e.toString());
            }
            @Override public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.warn("Cache clear failed ({}): {}", cache.getName(), e.toString());
            }
        };
    }
}
