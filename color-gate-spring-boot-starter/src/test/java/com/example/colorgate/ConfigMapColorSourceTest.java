package com.example.colorgate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.example.colorgate.ColorActivatedEvent.Reason;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.mockwebserver.Context;
import io.fabric8.mockwebserver.MockWebServer;

/**
 * The informer against a mock Kubernetes API (CRUD mode: list, watch, create, update and delete behave like a real
 * API server). Every test is one scenario from docs/negative-scenarios.md or the normal flow.
 */
class ConfigMapColorSourceTest {

    private static final String NS = "color-gate";
    private static final String NAME = "color-gate";
    private static final String KEY = "active-color";

    private final List<Object> events = new CopyOnWriteArrayList<>();
    private final ColorGate gate = new ColorGate(true, Color.GREEN);
    private final ColorGateReconciler reconciler = new ColorGateReconciler(gate, events::add);

    private final KubernetesCrudDispatcher storage = new KubernetesCrudDispatcher();
    private KubernetesMockServer server;
    private KubernetesClient client;
    private ConfigMapColorSource source;

    @AfterEach
    void tearDown() {
        reconciler.stop();
        if (source != null) {
            source.stop();
        }
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.destroy();
        }
    }

    @Test
    void startsActiveWhenTheConfigMapAlreadySaysMyColor() {
        startServer(0);
        setColor("GREEN");

        startSource();

        awaitState(GateState.ACTIVE);
        assertThat(events).containsExactly(new ColorActivatedEvent(Color.GREEN, Reason.STARTUP));
    }

    @Test
    void switchIsPushedWithoutPolling() {
        startServer(0);
        setColor("BLUE");
        startSource();
        awaitState(GateState.PASSIVE);

        setColor("GREEN");

        awaitState(GateState.ACTIVE);
        assertThat(events).containsExactly(new ColorActivatedEvent(Color.GREEN, Reason.SWITCH));
    }

    @Test
    void noneIsAnEmergencyStop() {
        startServer(0);
        setColor("GREEN");
        startSource();
        awaitState(GateState.ACTIVE);

        setColor("NONE");

        awaitState(GateState.PASSIVE);
        assertThat(events).contains(new ColorDeactivatedEvent(Color.GREEN, ActiveColor.NONE));
    }

    @Test
    void missingConfigMapAtStartupMeansPassiveUntilItIsCreated() {
        startServer(0);
        startSource();
        awaitState(GateState.PASSIVE);

        setColor("GREEN");

        awaitState(GateState.ACTIVE);
        assertThat(events).containsExactly(new ColorActivatedEvent(Color.GREEN, Reason.SWITCH));
    }

    @Test
    void invalidValueIsIgnoredAndStateKept() {
        startServer(0);
        setColor("GREEN");
        startSource();
        awaitState(GateState.ACTIVE);

        setColor("GREN");

        await().atMost(Duration.ofSeconds(5))
                .until(() -> String.valueOf(source.status().get("lastProblem")).contains("'GREN'"));
        assertThat(gate.getState()).isEqualTo(GateState.ACTIVE);
        assertThat(gate.isActive()).isTrue();
    }

    @Test
    void missingKeyIsIgnoredAndStateKept() {
        startServer(0);
        setColor("GREEN");
        startSource();
        awaitState(GateState.ACTIVE);

        setData(Map.of("something-else", "x"));

        await().atMost(Duration.ofSeconds(5))
                .until(() -> String.valueOf(source.status().get("lastProblem")).contains("<missing>"));
        assertThat(gate.isActive()).isTrue();
    }

    @Test
    void deletedConfigMapKeepsTheStateAndRecreatingItWorks() {
        startServer(0);
        setColor("GREEN");
        startSource();
        awaitState(GateState.ACTIVE);

        client.configMaps().inNamespace(NS).withName(NAME).delete();
        await().atMost(Duration.ofSeconds(5))
                .until(() -> String.valueOf(source.status().get("lastProblem")).contains("deleted"));
        assertThat(gate.isActive()).isTrue();

        setColor("BLUE");
        awaitState(GateState.PASSIVE);
    }

    @Test
    void forbiddenFailsStartupWithAClearMessage() {
        server = new KubernetesMockServer(false);
        server.init();
        server.expect().get().withPath("/api/v1/namespaces/" + NS + "/configmaps/" + NAME)
                .andReturn(403, "{\"kind\":\"Status\",\"code\":403,\"message\":\"forbidden\"}").always();
        client = server.createClient();
        source = new ConfigMapColorSource(client, reconciler, NS, NAME, KEY);

        assertThatThrownBy(source::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 403")
                .hasMessageContaining("Role and RoleBinding");
        assertThat(gate.isActive()).isFalse();
    }

    @Test
    void apiUnreachableAtStartupThenComesUp() throws Exception {
        int port = freePort();
        client = clientFor(port);
        source = new ConfigMapColorSource(client, reconciler, NS, NAME, KEY);

        source.start();                                       // must not throw: the API is just not there yet
        Thread.sleep(1500);
        assertThat(gate.getState()).isEqualTo(GateState.UNKNOWN);
        assertThat(gate.isActive()).isFalse();

        seed("GREEN");                                        // stored before the API comes up
        startServer(port);

        awaitState(GateState.ACTIVE, Duration.ofSeconds(45));
        assertThat(events).containsExactly(new ColorActivatedEvent(Color.GREEN, Reason.STARTUP));
    }

    @Test
    void apiOutageKeepsTheStateAndTheInformerReconnects() throws Exception {
        int port = freePort();
        startServer(port);
        client = clientFor(port);
        setColor("GREEN");
        source = new ConfigMapColorSource(client, reconciler, NS, NAME, KEY);
        source.start();
        awaitState(GateState.ACTIVE);

        server.destroy();                                      // API server goes away
        Thread.sleep(2000);
        assertThat(gate.isActive()).isTrue();                  // state kept during the outage

        startServer(port);                                     // API server back, with its data (as with etcd)
        setColor("BLUE");                                      // changed while this pod is still reconnecting

        awaitState(GateState.PASSIVE, Duration.ofSeconds(45)); // the informer reconnected and caught up
    }

    private void startSource() {
        source = new ConfigMapColorSource(client, reconciler, NS, NAME, KEY);
        source.start();
    }

    private void startServer(int port) {
        // The dispatcher holds the objects; reusing it across a restart keeps the data, like etcd behind a real API.
        server = new KubernetesMockServer(new Context(), new MockWebServer(), new HashMap<>(), storage, false);
        if (port == 0) {
            server.init();
            client = server.createClient();
        } else {
            server.init(InetAddress.getLoopbackAddress(), port);
        }
    }

    private KubernetesClient clientFor(int port) {
        Config config = new ConfigBuilder(Config.empty())
                .withMasterUrl("http://127.0.0.1:" + port)
                .withNamespace("green")
                .withConnectionTimeout(1000)
                .withRequestTimeout(2000)
                .build();
        return new KubernetesClientBuilder().withConfig(config).build();
    }

    private void setColor(String value) {
        setData(server, Map.of(KEY, value));
    }

    private void setData(Map<String, String> data) {
        setData(server, data);
    }

    /** Stores a value while the real API port is still down, through a temporary server on the same storage. */
    private void seed(String value) {
        KubernetesMockServer temporary = new KubernetesMockServer(new Context(), new MockWebServer(), new HashMap<>(),
                storage, false);
        temporary.init();
        try {
            setData(temporary, Map.of(KEY, value));
        } finally {
            temporary.destroy();
        }
    }

    private static void setData(KubernetesMockServer target, Map<String, String> data) {
        // Writes through the server's own client so it works while the test client is reconnecting.
        try (KubernetesClient admin = target.createClient()) {
            ConfigMap existing = admin.configMaps().inNamespace(NS).withName(NAME).get();
            if (existing == null) {
                admin.configMaps().inNamespace(NS).resource(new ConfigMapBuilder()
                        .withNewMetadata().withName(NAME).withNamespace(NS).endMetadata()
                        .withData(data).build()).create();
            } else {
                admin.configMaps().inNamespace(NS).withName(NAME).edit(configMap -> {
                    configMap.setData(new HashMap<>(data));
                    return configMap;
                });
            }
        }
    }

    private void awaitState(GateState state) {
        awaitState(state, Duration.ofSeconds(10));
    }

    private void awaitState(GateState state, Duration timeout) {
        await().atMost(timeout).until(() -> gate.getState() == state);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
