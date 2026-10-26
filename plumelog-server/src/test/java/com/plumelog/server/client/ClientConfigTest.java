package com.plumelog.server.client;

import com.plumelog.core.client.AbstractClient;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.context.annotation.ContextAnnotationAutowireCandidateResolver;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.env.MapPropertySource;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.util.Pool;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

public class ClientConfigTest {

    @Test
    public void shouldNotCreateRabbitMQClientInRedisMode() throws Exception {
        ClientConfig clientConfig = new ClientConfig();
        Field model = ClientConfig.class.getDeclaredField("model");
        model.setAccessible(true);
        model.set(clientConfig, "redis");

        Assert.assertNull(clientConfig.initRabbitMQClient());
    }

    @Test
    public void shouldDisableTlsByDefault() throws Exception {
        ClientConfig config = configure(new HashMap<>());
        assertTls(config.initRedisClient(), false, false);
        assertTls(config.initRedisQueueClient(), false, false);
    }

    @Test
    public void shouldEnableManagementTlsWithoutCertificateVerificationByDefault() throws Exception {
        Map<String, Object> properties = new HashMap<>();
        properties.put("plumelog.redis.tls", "true");
        ClientConfig config = configure(properties);
        assertTls(config.initRedisClient(), true, false);
        assertTls(config.initRedisQueueClient(), false, false);
    }

    @Test
    public void shouldReuseQueueTlsWhenManagementHostIsMissing() throws Exception {
        Map<String, Object> properties = new HashMap<>();
        properties.put("plumelog.redis.redisHost", "");
        properties.put("plumelog.queue.redis.tls", "true");
        ClientConfig config = configure(properties);
        assertTls(config.initRedisClient(), true, false);
        assertTls(config.initRedisQueueClient(), true, false);
    }

    @Test
    public void shouldApplyCertificateVerificationWhenRequested() throws Exception {
        Map<String, Object> properties = new HashMap<>();
        properties.put("plumelog.queue.redis.tls", "true");
        properties.put("plumelog.queue.redis.tlsVerify", "true");
        ClientConfig config = configure(properties);
        assertTls(config.initRedisQueueClient(), true, true);
        assertTls(config.initRedisClient(), false, false);
    }

    private ClientConfig configure(Map<String, Object> properties) {
        properties.putIfAbsent("plumelog.redis.redisHost", "127.0.0.1:6379");
        properties.putIfAbsent("plumelog.queue.redis.redisHost", "127.0.0.1:6379");
        try (StaticApplicationContext context = new StaticApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
            context.getDefaultListableBeanFactory().setAutowireCandidateResolver(new ContextAnnotationAutowireCandidateResolver());
            context.getDefaultListableBeanFactory().addEmbeddedValueResolver(context.getEnvironment()::resolveRequiredPlaceholders);
            AutowiredAnnotationBeanPostProcessor processor = new AutowiredAnnotationBeanPostProcessor();
            processor.setBeanFactory(context.getDefaultListableBeanFactory());
            ClientConfig config = new ClientConfig();
            processor.processInjection(config);
            return config;
        }
    }

    private void assertTls(AbstractClient client, boolean enabled, boolean verify) throws Exception {
        Pool<?> pool = (Pool<?>) field(client, "jedisPool");
        try {
            Field internalPool = Pool.class.getDeclaredField("internalPool");
            internalPool.setAccessible(true);
            Object factory = ((GenericObjectPool<?>) internalPool.get(pool)).getFactory();
            JedisClientConfig config = (JedisClientConfig) field(factory, "clientConfig");
            Assert.assertEquals(enabled, config.isSsl());
            if (enabled && !verify) {
                Assert.assertNotNull(config.getSslSocketFactory());
                Assert.assertTrue(config.getHostnameVerifier().verify("untrusted.invalid", null));
            } else if (enabled) {
                Assert.assertEquals("HTTPS", config.getSslParameters().getEndpointIdentificationAlgorithm());
            }
        } finally {
            pool.close();
        }
    }

    private Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
