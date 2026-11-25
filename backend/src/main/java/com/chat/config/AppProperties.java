package com.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String instanceId = "node-local";
    private boolean seedDemoUsers = true;
    private Ws ws = new Ws();
    private Auth auth = new Auth();
    private Broker broker = new Broker();

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public boolean isSeedDemoUsers() {
        return seedDemoUsers;
    }

    public void setSeedDemoUsers(boolean seedDemoUsers) {
        this.seedDemoUsers = seedDemoUsers;
    }

    public Ws getWs() {
        return ws;
    }

    public void setWs(Ws ws) {
        this.ws = ws;
    }

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth;
    }

    public Broker getBroker() {
        return broker;
    }

    public void setBroker(Broker broker) {
        this.broker = broker;
    }

    public static class Ws {
        private int port = 8081;
        private int maxPayloadBytes = 65536;
        private int outboundQueueCapacity = 256;
        private int heartbeatIntervalSeconds = 15;
        private int heartbeatTimeoutSeconds = 45;
        private String allowedOrigins = "http://localhost:8080,http://localhost:5173";

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public int getMaxPayloadBytes() {
            return maxPayloadBytes;
        }

        public void setMaxPayloadBytes(int maxPayloadBytes) {
            this.maxPayloadBytes = maxPayloadBytes;
        }

        public int getOutboundQueueCapacity() {
            return outboundQueueCapacity;
        }

        public void setOutboundQueueCapacity(int outboundQueueCapacity) {
            this.outboundQueueCapacity = outboundQueueCapacity;
        }

        public int getHeartbeatIntervalSeconds() {
            return heartbeatIntervalSeconds;
        }

        public void setHeartbeatIntervalSeconds(int heartbeatIntervalSeconds) {
            this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        }

        public int getHeartbeatTimeoutSeconds() {
            return heartbeatTimeoutSeconds;
        }

        public void setHeartbeatTimeoutSeconds(int heartbeatTimeoutSeconds) {
            this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
        }

        public String getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(String allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }

        public Set<String> parsedAllowedOrigins() {
            if (allowedOrigins == null || allowedOrigins.isBlank()) {
                return Set.of();
            }
            return Arrays.stream(allowedOrigins.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
        }
    }

    public static class Auth {
        private String sessionCookieName = "CHAT_SESSION";
        private int sessionTtlHours = 168;
        private boolean cookieSecure = false;

        public String getSessionCookieName() {
            return sessionCookieName;
        }

        public void setSessionCookieName(String sessionCookieName) {
            this.sessionCookieName = sessionCookieName;
        }

        public int getSessionTtlHours() {
            return sessionTtlHours;
        }

        public void setSessionTtlHours(int sessionTtlHours) {
            this.sessionTtlHours = sessionTtlHours;
        }

        public boolean isCookieSecure() {
            return cookieSecure;
        }

        public void setCookieSecure(boolean cookieSecure) {
            this.cookieSecure = cookieSecure;
        }
    }

    public static class Broker {
        private String type = "rabbitmq";
        private String exchange = "chat.events";

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getExchange() {
            return exchange;
        }

        public void setExchange(String exchange) {
            this.exchange = exchange;
        }
    }
}
