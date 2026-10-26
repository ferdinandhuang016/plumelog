package com.plumelog.core.redis;

import redis.clients.jedis.DefaultJedisClientConfig;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;

final class RedisTlsConfig {
    private RedisTlsConfig() {
    }

    static DefaultJedisClientConfig create(String password, int database, int timeout, boolean verify) {
        DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
                .password(password == null || password.isEmpty() ? null : password)
                .database(database).connectionTimeoutMillis(timeout).socketTimeoutMillis(timeout).ssl(true);
        if (verify) {
            SSLParameters parameters = new SSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            return builder.sslParameters(parameters).build();
        }
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }

                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }
            }}, null);
            return builder.sslSocketFactory(context.getSocketFactory())
                    .hostnameVerifier((hostname, session) -> true).build();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot initialize Redis TLS", e);
        }
    }
}
