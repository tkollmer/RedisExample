package com.df.queue.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ReadFrom;
import io.lettuce.core.TimeoutOptions;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis connection and template configuration.
 *
 * <p>Key features:
 * <ul>
 *   <li><b>Connection pooling</b> — 16 pooled connections via commons-pool2,
 *       enabling concurrent pipelined operations without contention</li>
 *   <li><b>Sentinel-aware</b> — Lettuce discovers the master via Sentinel and
 *       auto-reconnects on failover</li>
 *   <li><b>Read distribution</b> — {@code REPLICA_PREFERRED} routes reads to
 *       replicas, falling back to master if none are available</li>
 *   <li><b>Aggressive timeouts</b> — 2-second command timeout for fast failure
 *       detection during crash testing</li>
 * </ul>
 */
@Configuration
public class RedisConfig {

    /**
     * Configures Lettuce with connection pooling and Sentinel read distribution.
     *
     * <p>Pool sizing:
     * <ul>
     *   <li>maxTotal=16 — supports concurrent pipelines from tick, detect, cleanup, and broadcast threads</li>
     *   <li>maxIdle=8 — keeps warm connections ready</li>
     *   <li>minIdle=2 — ensures baseline availability</li>
     * </ul>
     */
    @Bean
    public LettuceClientConfiguration lettuceClientConfiguration() {
        GenericObjectPoolConfig<?> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(16);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(2);

        return LettucePoolingClientConfiguration.builder()
                .poolConfig(poolConfig)
                .readFrom(ReadFrom.REPLICA_PREFERRED)
                .commandTimeout(Duration.ofSeconds(2))
                .clientOptions(ClientOptions.builder()
                        .autoReconnect(true)
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .timeoutOptions(TimeoutOptions.enabled(Duration.ofSeconds(2)))
                        .build())
                .build();
    }

    /**
     * Creates the RedisTemplate with String key serialization and JSON value serialization.
     * Hash keys are also serialized as Strings for readable key names in Redis.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }
}
