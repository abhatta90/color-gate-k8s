package com.example.colorgate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;

/**
 * Tells the {@link ColorGateReconciler} the active color, pushed by Kubernetes: an informer on one ConfigMap.
 * <p>
 * The informer lists the ConfigMap once, then keeps a watch open; Kubernetes pushes every change. It reconnects and
 * catches up by itself. No polling.
 * <p>
 * Only a valid value ({@code BLUE}, {@code GREEN}, {@code NONE}) is handed over. An invalid value, a missing key or a
 * deleted ConfigMap is logged and ignored, so a running pod keeps its state. A ConfigMap that does not exist when
 * the pod starts means "nobody active".
 */
public class ConfigMapColorSource {

    private static final Logger log = LoggerFactory.getLogger(ConfigMapColorSource.class);

    /** Optional annotation written by the switch step: epoch millis, used to log how fast the push arrived. */
    static final String SWITCHED_AT_ANNOTATION = "colorgate/switched-at";

    private final KubernetesClient client;
    private final ColorGateReconciler reconciler;
    private final String namespace;
    private final String name;
    private final String key;

    private volatile SharedIndexInformer<ConfigMap> informer;
    private volatile String lastValue;
    private volatile Instant lastEventAt;
    private volatile String lastProblem;

    ConfigMapColorSource(KubernetesClient client, ColorGateReconciler reconciler,
                         String namespace, String name, String key) {
        this.client = client;
        this.reconciler = reconciler;
        this.namespace = namespace;
        this.name = name;
        this.key = key;
    }

    /**
     * Reads the ConfigMap once, then starts the informer.
     *
     * @throws IllegalStateException if Kubernetes refuses access (HTTP 401/403): a permanent misconfiguration that
     *                               must fail the deployment instead of leaving the pod passive forever
     */
    void start() {
        try {
            ConfigMap configMap = client.configMaps().inNamespace(namespace).withName(name).get();
            if (configMap == null) {
                noConfigMap();
            } else {
                accept(configMap, "first read");
            }
        } catch (KubernetesClientException e) {
            if (e.getCode() == 401 || e.getCode() == 403) {
                throw new IllegalStateException("Color gate: Kubernetes refused to let this pod read ConfigMap "
                        + where() + " (HTTP " + e.getCode() + "). Check the color-gate Role and RoleBinding.", e);
            }
            problem("cannot reach the Kubernetes API yet (" + e.getMessage() + "); "
                    + "background work stays off until it can, retrying");
        }

        SharedIndexInformer<ConfigMap> started = client.configMaps().inNamespace(namespace).withName(name)
                .runnableInformer(0);                 // 0 = no periodic resync: react to real changes only
        started.addEventHandler(new Handler());
        started.exceptionHandler((isStarted, error) -> {
            problem("connection to the Kubernetes API failed (" + error.getMessage() + "); keeping the current "
                    + "state and reconnecting");
            return true;                              // always keep trying
        });
        informer = started;
        started.start().whenComplete((ignored, error) -> {
            if (error != null) {
                problem("informer could not start (" + error.getMessage() + ")");
            }
        });
    }

    void stop() {
        SharedIndexInformer<ConfigMap> current = informer;
        if (current != null) {
            current.stop();
        }
    }

    Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("configMap", where());
        status.put("key", key);
        status.put("lastValue", lastValue);
        status.put("lastEventAt", lastEventAt);
        status.put("watching", isWatching());
        status.put("synced", isSynced());
        status.put("lastProblem", lastProblem);
        return status;
    }

    /** True while the push connection to the Kubernetes API is up. */
    boolean isWatching() {
        SharedIndexInformer<ConfigMap> current = informer;
        return current != null && current.isWatching();
    }

    /** True once the ConfigMap has been read at least once. */
    boolean isSynced() {
        SharedIndexInformer<ConfigMap> current = informer;
        return current != null && current.hasSynced();
    }

    private void accept(ConfigMap configMap, String how) {
        lastEventAt = Instant.now();
        String raw = configMap.getData() == null ? null : configMap.getData().get(key);
        lastValue = raw;
        ActiveColor value = ActiveColor.parse(raw);
        if (value == null) {
            problem("ConfigMap " + where() + " has " + key + "=" + (raw == null ? "<missing>" : "'" + raw + "'")
                    + ", expected BLUE, GREEN or NONE; ignoring it and keeping the current state");
            return;
        }
        lastProblem = null;
        if (value != reconciler.getDesired()) {
            log.info("Color gate: active color is {} ({}{})", value, how, latency(configMap));
        }
        reconciler.desire(value);
    }

    private void noConfigMap() {
        lastEventAt = Instant.now();
        log.warn("Color gate: ConfigMap {} does not exist: no color is active, background work stays off", where());
        reconciler.desireIfUnknown(ActiveColor.NONE);
    }

    private void problem(String message) {
        lastProblem = message;
        log.warn("Color gate: {}", message);
    }

    private String latency(ConfigMap configMap) {
        Map<String, String> annotations = configMap.getMetadata().getAnnotations();
        String switchedAt = annotations == null ? null : annotations.get(SWITCHED_AT_ANNOTATION);
        if (switchedAt == null) {
            return "";
        }
        try {
            return ", " + (System.currentTimeMillis() - Long.parseLong(switchedAt.trim())) + " ms after the switch";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private String where() {
        return namespace + "/" + name;
    }

    private final class Handler implements ResourceEventHandler<ConfigMap> {

        @Override
        public void onAdd(ConfigMap configMap) {
            accept(configMap, "pushed");
        }

        @Override
        public void onUpdate(ConfigMap oldConfigMap, ConfigMap newConfigMap) {
            accept(newConfigMap, "pushed");
        }

        @Override
        public void onDelete(ConfigMap configMap, boolean deletedFinalStateUnknown) {
            lastEventAt = Instant.now();
            lastValue = null;
            log.error("Color gate: ConfigMap {} was DELETED; keeping the current state. "
                    + "To stop background work on purpose, set {}=NONE instead.", where(), key);
            lastProblem = "ConfigMap deleted; current state kept";
        }

        @Override
        public void onList(String resourceVersion, boolean remainedEmpty) {
            if (remainedEmpty) {
                noConfigMap();                    // a (re-)list found no ConfigMap, and there was none before
            }
        }
    }
}
