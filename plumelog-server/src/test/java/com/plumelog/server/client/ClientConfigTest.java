package com.plumelog.server.client;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;

public class ClientConfigTest {

    @Test
    public void shouldNotCreateRabbitMQClientInRedisMode() throws Exception {
        ClientConfig clientConfig = new ClientConfig();
        Field model = ClientConfig.class.getDeclaredField("model");
        model.setAccessible(true);
        model.set(clientConfig, "redis");

        Assert.assertNull(clientConfig.initRabbitMQClient());
    }
}
