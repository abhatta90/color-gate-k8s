package com.example.colorgate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.mockwebserver.Context;
import io.fabric8.mockwebserver.MockWebServer;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The library wired into a Spring context, against a mock Kubernetes API.
 */
class ColorGateAutoConfigurationTest {

    private KubernetesMockServer server;

    @BeforeEach
    void startServer() {
        server = new KubernetesMockServer(new Context(), new MockWebServer(), new HashMap<>(),
                new KubernetesCrudDispatcher(), false);
        server.init();
    }

    @AfterEach
    void stopServer() {
        server.destroy();
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, ColorGateAutoConfiguration.class))
                .withBean(KubernetesClient.class, server::createClient)
                .withUserConfiguration(TestConfig.class)
                .withPropertyValues("colorgate.my-color=GREEN");
    }

    @Test
    void passivePodSkipsGatedWorkButRunsRunInAllColors() {
        setColor("BLUE");
        runner().run(context -> {
            context.getBean(ColorGateLifecycle.class).start();
            awaitState(context.getBean(ColorGate.class), GateState.PASSIVE);

            Tasks tasks = context.getBean(Tasks.class);
            tasks.scheduled();
            tasks.everyColor();
            tasks.gated();

            assertThat(tasks.calls()).containsExactly("everyColor");
        });
    }

    @Test
    void switchActivatesThePodAndGatedWorkRuns() {
        setColor("BLUE");
        runner().run(context -> {
            context.getBean(ColorGateLifecycle.class).start();
            ColorGate gate = context.getBean(ColorGate.class);
            awaitState(gate, GateState.PASSIVE);

            setColor("GREEN");
            awaitState(gate, GateState.ACTIVE);

            Tasks tasks = context.getBean(Tasks.class);
            tasks.scheduled();
            tasks.gated();
            assertThat(tasks.calls()).containsExactly("activated:SWITCH", "scheduled", "gated");
        });
    }

    @Test
    void metricsReportTheGateAndThePushConnection() {
        setColor("GREEN");
        runner().run(context -> {
            context.getBean(ColorGateLifecycle.class).start();
            awaitState(context.getBean(ColorGate.class), GateState.ACTIVE);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            context.getBean(MeterBinder.class).bindTo(registry);

            await().atMost(Duration.ofSeconds(10))
                    .until(() -> registry.get("colorgate.watching").gauge().value() == 1.0);
            assertThat(registry.get("colorgate.active").tag("color", "GREEN").gauge().value()).isEqualTo(1.0);
            assertThat(registry.get("colorgate.synced").gauge().value()).isEqualTo(1.0);
        });
    }

    @Test
    void shutdownClosesTheGateBeforeAnythingElse() {
        setColor("GREEN");
        runner().run(context -> {
            context.getBean(ColorGateLifecycle.class).start();
            ColorGate gate = context.getBean(ColorGate.class);
            awaitState(gate, GateState.ACTIVE);

            context.close();

            assertThat(gate.getState()).isEqualTo(GateState.STOPPED);
            assertThat(gate.isActive()).isFalse();
        });
    }

    @Test
    void disabledGateNeedsNoKubernetesAndIsAlwaysActive() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, ColorGateAutoConfiguration.class))
                .withUserConfiguration(TestConfig.class)
                .withPropertyValues("colorgate.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(KubernetesClient.class);
                    context.getBean(ColorGateLifecycle.class).start();
                    await().atMost(Duration.ofSeconds(5)).until(() -> context.getBean(ColorGate.class).isActive());

                    Tasks tasks = context.getBean(Tasks.class);
                    tasks.scheduled();
                    assertThat(tasks.calls()).containsExactly("activated:STARTUP", "scheduled");
                });
    }

    @Test
    void wrongColorFailsStartup() {
        runner().withPropertyValues("colorgate.my-color=RED").run(context ->
                assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("'RED'"));
    }

    private void setColor(String value) {
        try (KubernetesClient admin = server.createClient()) {
            if (admin.configMaps().inNamespace("color-gate").withName("color-gate").get() == null) {
                admin.configMaps().inNamespace("color-gate").resource(new ConfigMapBuilder()
                        .withNewMetadata().withName("color-gate").withNamespace("color-gate").endMetadata()
                        .withData(Map.of("active-color", value)).build()).create();
            } else {
                admin.configMaps().inNamespace("color-gate").withName("color-gate").edit(configMap -> {
                    configMap.setData(new HashMap<>(Map.of("active-color", value)));
                    return configMap;
                });
            }
        }
    }

    private static void awaitState(ColorGate gate, GateState state) {
        await().atMost(Duration.ofSeconds(10)).until(() -> gate.getState() == state);
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        Tasks tasks() {
            return new Tasks();
        }
    }

    static class Tasks {

        private final List<String> calls = new CopyOnWriteArrayList<>();

        @Scheduled(fixedDelay = 1000)
        public void scheduled() {
            calls.add("scheduled");
        }

        @RunInAllColors
        @Scheduled(fixedDelay = 1000)
        public void everyColor() {
            calls.add("everyColor");
        }

        @ColorGated
        public void gated() {
            calls.add("gated");
        }

        @OnColorActivated
        public void onActivated(ColorActivatedEvent event) {
            calls.add("activated:" + event.reason());
        }

        @OnColorDeactivated
        public void onDeactivated() {
            calls.add("deactivated");
        }

        public List<String> calls() {
            return calls;
        }
    }
}
