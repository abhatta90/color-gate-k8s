package com.example.colorgate;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("colorgate")
public class ColorGateProperties {

    /** Set to false where there is no blue/green (local, dev): the pod is then always active. */
    private boolean enabled = true;

    /**
     * This pod's color, BLUE or GREEN. Defaults to the APP_COLOR environment variable, then to the pod's namespace
     * ("blue"/"*-blue" or "green"/"*-green"). If both are known they must agree.
     */
    private String myColor;

    private final ConfigMap configMap = new ConfigMap();

    private final Kubernetes kubernetes = new Kubernetes();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMyColor() {
        return myColor;
    }

    public void setMyColor(String myColor) {
        this.myColor = myColor;
    }

    public ConfigMap getConfigMap() {
        return configMap;
    }

    public Kubernetes getKubernetes() {
        return kubernetes;
    }

    /** Where the active color lives. */
    public static class ConfigMap {

        /** Namespace of the ConfigMap (one per cluster, readable by the blue and green pods). */
        private String namespace = "color-gate";

        /** Name of the ConfigMap. */
        private String name = "color-gate";

        /** Key holding BLUE, GREEN or NONE. */
        private String key = "active-color";

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }
    }

    /** Timeouts for the client the library creates (ignored if the service defines its own KubernetesClient bean). */
    public static class Kubernetes {

        private Duration connectionTimeout = Duration.ofSeconds(5);

        private Duration requestTimeout = Duration.ofSeconds(10);

        public Duration getConnectionTimeout() {
            return connectionTimeout;
        }

        public void setConnectionTimeout(Duration connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }
    }
}
