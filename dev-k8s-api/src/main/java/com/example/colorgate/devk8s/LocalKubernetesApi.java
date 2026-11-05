package com.example.colorgate.devk8s;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.mockwebserver.Context;
import io.fabric8.mockwebserver.MockWebServer;

/**
 * Development only: an in-memory fake Kubernetes API on http://127.0.0.1:8001, so the color gate can be tried without
 * a cluster. It stores objects (create, read, update, delete) and pushes changes to watchers, like a real API server,
 * but has no authentication, no RBAC and loses everything when stopped.
 * <p>
 * Port 8001 is also where {@code kubectl proxy} serves a real cluster's API, so the apps and scripts work the same way
 * against either.
 */
public final class LocalKubernetesApi {

    private LocalKubernetesApi() {
    }

    public static void main(String[] args) throws InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8001;
        // The mock web server logs every request through java.util.logging; keep the console readable.
        Logger.getLogger("io.fabric8.mockwebserver").setLevel(Level.WARNING);

        KubernetesMockServer server = new KubernetesMockServer(new Context(), new MockWebServer(), new HashMap<>(),
                new KubernetesCrudDispatcher(), false);
        server.init(InetAddress.getLoopbackAddress(), port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::destroy, "local-k8s-api-shutdown"));

        System.out.println();
        System.out.println("Local fake Kubernetes API running on http://127.0.0.1:" + port + " (development only)");
        System.out.println("  apps:    KUBERNETES_MASTER=http://127.0.0.1:" + port);
        System.out.println("  switch:  scripts/switch.sh GREEN");
        System.out.println("  data is in memory only; stop with Ctrl+C or the IntelliJ stop button");
        System.out.println();

        new CountDownLatch(1).await();                         // run until the process is stopped
    }
}
