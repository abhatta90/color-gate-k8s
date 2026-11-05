package com.example.colorgate;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.micrometer.core.instrument.binder.MeterBinder;

@AutoConfiguration
@EnableConfigurationProperties(ColorGateProperties.class)
public class ColorGateAutoConfiguration {

    @Bean
    public ColorGateReconciler colorGateReconciler(ColorGate colorGate, ApplicationEventPublisher publisher) {
        return new ColorGateReconciler(colorGate, publisher);
    }

    @Bean
    ColorGateLifecycle colorGateLifecycle(ColorGateReconciler reconciler, ObjectProvider<ConfigMapColorSource> source,
                                          ApplicationContext applicationContext) {
        return new ColorGateLifecycle(reconciler, source.getIfAvailable(), applicationContext);
    }

    @Bean
    public ColorGateAspect colorGateAspect(ObjectProvider<ColorGate> gate) {
        return new ColorGateAspect(gate);
    }

    /** Normal mode: the active color comes from a ConfigMap, pushed by an informer. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "colorgate", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class KubernetesConfiguration {

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        KubernetesClient colorGateKubernetesClient(ColorGateProperties properties) {
            // In a pod: the pod's ServiceAccount and namespace. Locally: ~/.kube/config or KUBERNETES_MASTER.
            Config config = Config.autoConfigure(null);
            config.setConnectionTimeout((int) properties.getKubernetes().getConnectionTimeout().toMillis());
            config.setRequestTimeout((int) properties.getKubernetes().getRequestTimeout().toMillis());
            return new KubernetesClientBuilder().withConfig(config).build();
        }

        @Bean
        ColorGate colorGate(ColorGateProperties properties, Environment environment, KubernetesClient client) {
            String configured = StringUtils.hasText(properties.getMyColor())
                    ? properties.getMyColor()
                    : environment.getProperty("APP_COLOR");
            return new ColorGate(true, MyColorResolver.resolve(configured, client.getNamespace()));
        }

        @Bean
        ConfigMapColorSource colorGateSource(KubernetesClient client, ColorGateReconciler reconciler,
                                             ColorGateProperties properties) {
            ColorGateProperties.ConfigMap configMap = properties.getConfigMap();
            return new ConfigMapColorSource(client, reconciler,
                    configMap.getNamespace(), configMap.getName(), configMap.getKey());
        }
    }

    /** {@code colorgate.enabled=false} (local, dev, no blue/green): always active after startup. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "colorgate", name = "enabled", havingValue = "false")
    static class DisabledConfiguration {

        @Bean
        ColorGate colorGate() {
            return new ColorGate(false, null);
        }
    }

    /** Gauges for alerting, when Micrometer is on the classpath (e.g. with actuator). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterBinder.class)
    static class MetricsConfiguration {

        @Bean
        MeterBinder colorGateMetrics(ColorGate gate, ObjectProvider<ConfigMapColorSource> source) {
            return new ColorGateMetrics(gate, source.getIfAvailable());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Endpoint.class)
    static class EndpointConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnAvailableEndpoint
        ColorGateEndpoint colorGateEndpoint(ColorGate gate, ColorGateReconciler reconciler,
                                            ObjectProvider<ConfigMapColorSource> source) {
            return new ColorGateEndpoint(gate, reconciler, source.getIfAvailable());
        }
    }
}
