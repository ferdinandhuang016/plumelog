package com.plumelog.core.redis;

import com.plumelog.core.client.AbstractClient;
import com.plumelog.core.exception.LogQueueConnectException;
import org.junit.Test;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.util.Pool;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.*;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

public class RedisTlsTest {
    @Test
    public void existingConstructorUsesPlaintext() throws Exception {
        try (RedisEndpoint endpoint = new RedisEndpoint(false)) {
            assertRead(new RedisClient("127.0.0.1", endpoint.port(), null, 0));
        }
    }

    @Test
    public void standaloneAcceptsSelfSignedCertificateAndWrongHostname() throws Exception {
        try (RedisEndpoint endpoint = new RedisEndpoint(true)) {
            assertRead(new RedisClient("127.0.0.1", endpoint.port(), "secret", 2, true, false));
            assertTrue(endpoint.commands.contains("AUTH secret"));
            assertTrue(endpoint.commands.contains("SELECT 2"));
        }
    }

    @Test
    public void verificationRejectsSelfSignedCertificate() throws Exception {
        try (RedisEndpoint endpoint = new RedisEndpoint(true)) {
            RedisClient client = new RedisClient("127.0.0.1", endpoint.port(), null, 0, true, true);
            try {
                assertThrows(LogQueueConnectException.class, () -> client.pushMessage("logs", "message"));
                assertTrue("No Redis command should pass the failed TLS handshake", endpoint.commands.isEmpty());
            } finally {
                closeClient(client);
            }
        }
    }

    @Test
    public void existingClusterConstructorUsesPlaintext() throws Exception {
        try (RedisEndpoint endpoint = new RedisEndpoint(false)) {
            assertRead(new RedisClusterClient("127.0.0.1:" + endpoint.port(), null));
        }
    }

    @Test
    public void existingSentinelConstructorUsesPlaintext() throws Exception {
        // The legacy Jedis Sentinel constructor maps loopback addresses to the local host address.
        try (RedisEndpoint endpoint = new RedisEndpoint(false, InetAddress.getLocalHost())) {
            assertRead(new RedisSentinelClient(endpoint.host() + ":" + endpoint.port(), "master", null, 0));
        }
    }

    @Test
    public void clusterUsesTlsForDiscoveryAndCommands() throws Exception {
        try (RedisEndpoint endpoint = new RedisEndpoint(true)) {
            assertRead(new RedisClusterClient("127.0.0.1:" + endpoint.port(), null, true, false));
            assertTrue(endpoint.commands.toString(), endpoint.commands.stream().anyMatch(command -> command.equalsIgnoreCase("CLUSTER SLOTS")));
        }
    }

    @Test
    public void sentinelUsesTlsForDiscoveryAndMasterCommands() throws Exception {
        try (RedisEndpoint endpoint = new RedisEndpoint(true)) {
            assertRead(new RedisSentinelClient("127.0.0.1:" + endpoint.port(), "master", "secret", 2, true, false));
            assertTrue(endpoint.commands.contains("SENTINEL get-master-addr-by-name master"));
            assertTrue(endpoint.commands.contains("SELECT 2"));
        }
    }

    private void assertRead(AbstractClient client) throws Exception {
        try {
            client.pushMessage("logs", "message");
            assertEquals("message", client.getMessage("logs"));
        } finally {
            closeClient(client);
        }
    }

    private void closeClient(AbstractClient client) throws Exception {
        String fieldName = client instanceof RedisClusterClient ? "jedisCluster"
                : client instanceof RedisSentinelClient ? "jedisSentinelPool" : "jedisPool";
        Field field = client.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        Object connection = field.get(client);
        if (connection instanceof JedisCluster) {
            ((JedisCluster) connection).close();
        } else {
            ((Pool<?>) connection).close();
        }
    }

    // Minimal RESP endpoint: the same listener acts as a Redis master, cluster node and Sentinel.
    private static class RedisEndpoint implements AutoCloseable {
        private final ServerSocket listener;
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private final List<String> commands = new CopyOnWriteArrayList<>();

        RedisEndpoint(boolean tls) throws Exception {
            this(tls, InetAddress.getByName("127.0.0.1"));
        }

        RedisEndpoint(boolean tls, InetAddress address) throws Exception {
            if (tls) {
                KeyStore keyStore = KeyStore.getInstance("PKCS12");
                try (InputStream input = getClass().getResourceAsStream("/redis-test.p12")) {
                    keyStore.load(input, "test-password".toCharArray());
                }
                KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keys.init(keyStore, "test-password".toCharArray());
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(keys.getKeyManagers(), null, null);
                listener = context.getServerSocketFactory().createServerSocket(0, 50, address);
            } else {
                listener = new ServerSocket(0, 50, address);
            }
            Thread acceptor = new Thread(() -> {
                while (!listener.isClosed()) {
                    try {
                        Socket socket = listener.accept();
                        sockets.add(socket);
                        Thread worker = new Thread(() -> serve(socket));
                        worker.setDaemon(true);
                        worker.start();
                    } catch (IOException e) {
                        if (!listener.isClosed()) {
                            throw new UncheckedIOException(e);
                        }
                    }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return listener.getLocalPort();
        }

        String host() {
            return listener.getInetAddress().getHostAddress();
        }

        private void serve(Socket socket) {
            try (Socket connection = socket) {
                BufferedReader input = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                Writer output = new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8);
                String header;
                while ((header = input.readLine()) != null) {
                    int count = Integer.parseInt(header.substring(1));
                    List<String> command = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        input.readLine();
                        command.add(input.readLine());
                    }
                    commands.add(String.join(" ", command));
                    String name = command.get(0).toUpperCase();
                    if ("PING".equals(name)) {
                        output.write("+PONG\r\n");
                    } else if ("LPOP".equals(name)) {
                        output.write("$7\r\nmessage\r\n");
                    } else if ("RPUSH".equals(name)) {
                        output.write(":1\r\n");
                    } else if ("CLUSTER".equals(name)) {
                        output.write("*1\r\n*3\r\n:0\r\n:16383\r\n*2\r\n$" + host().length() + "\r\n" + host() + "\r\n:" + port() + "\r\n");
                    } else if ("SENTINEL".equals(name)) {
                        String port = String.valueOf(port());
                        output.write("*2\r\n$" + host().length() + "\r\n" + host() + "\r\n$" + port.length() + "\r\n" + port + "\r\n");
                    } else if ("SUBSCRIBE".equals(name)) {
                        output.write("*3\r\n$9\r\nsubscribe\r\n$14\r\n+switch-master\r\n:1\r\n");
                    } else {
                        output.write("+OK\r\n");
                    }
                    output.flush();
                }
            } catch (IOException ignored) {
                // TLS rejection and closing a Sentinel subscription terminate the connection.
            }
        }

        @Override
        public void close() throws IOException {
            listener.close();
            for (Socket socket : sockets) {
                socket.close();
            }
        }
    }
}
