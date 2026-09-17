package com.geoplan.rfid.agent.http;

import com.geoplan.rfid.agent.config.AgentConfig;
import com.geoplan.rfid.agent.util.Log;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * TLS material for the control server.
 *
 * Point AGENT_TLS_KEYSTORE at a real keystore for anything beyond a desk. When
 * the file is missing the agent generates a self signed certificate with keytool
 * from the running JDK, which is why the middleware needs
 * READER_AGENT_TLS_REJECT_UNAUTHORIZED=false on a LAN desk.
 */
public final class Tls {

    private static final String ALIAS = "rfid-agent";

    private Tls() {
    }

    public static SSLContext createContext(AgentConfig config) throws Exception {
        Path keystorePath = Paths.get(config.keystorePath).toAbsolutePath();

        if (!Files.exists(keystorePath)) {
            generateSelfSigned(config, keystorePath);
        }

        char[] password = config.keystorePassword.toCharArray();
        KeyStore keystore = KeyStore.getInstance(config.keystoreType);

        try (InputStream input = Files.newInputStream(keystorePath)) {
            keystore.load(input, password);
        }

        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keystore, password);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);

        Log.info("TLS keystore loaded from " + keystorePath);

        return context;
    }

    private static void generateSelfSigned(AgentConfig config, Path keystorePath) throws Exception {
        Path parent = keystorePath.getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        String subjectAltNames = subjectAltNames(config);

        List<String> command = new ArrayList<>(List.of(
                keytool(),
                "-genkeypair",
                "-alias", ALIAS,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-sigalg", "SHA256withRSA",
                "-validity", "3650",
                "-storetype", config.keystoreType,
                "-keystore", keystorePath.toString(),
                "-storepass", config.keystorePassword,
                "-keypass", config.keystorePassword,
                "-dname", "CN=ssi-rfid-agent,OU=RMK,O=Geoplan Philippines,C=PH",
                "-ext", "SAN=" + subjectAltNames,
                "-ext", "EKU=serverAuth"
        ));

        Log.info("No keystore at " + keystorePath + ". Generating a self signed certificate for " + subjectAltNames);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes());

        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("keytool timed out while generating the agent certificate");
        }

        if (process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed (exit " + process.exitValue() + "): " + output.trim());
        }

        Log.info("Self signed certificate written to " + keystorePath);
    }

    /**
     * localhost plus every IPv4 address on the box, so the middleware can reach
     * the agent by whatever address the reader is registered with.
     */
    private static String subjectAltNames(AgentConfig config) {
        Set<String> entries = new LinkedHashSet<>();

        entries.add("dns:localhost");
        entries.add("ip:127.0.0.1");

        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!networkInterface.isUp()) {
                    continue;
                }

                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        entries.add("ip:" + address.getHostAddress());
                    }
                }
            }
        } catch (Exception e) {
            Log.warn("Could not enumerate local addresses for the certificate", e);
        }

        try {
            String hostname = InetAddress.getLocalHost().getHostName();

            if (hostname != null && !hostname.isBlank()) {
                entries.add("dns:" + hostname);
            }
        } catch (Exception ignored) {
            /* Hostname is a nicety, the IP entries are what the middleware uses. */
        }

        for (String extra : config.extraSubjectAltNames.split(",")) {
            String trimmed = extra.trim();

            if (!trimmed.isEmpty()) {
                entries.add(trimmed);
            }
        }

        return String.join(",", entries);
    }

    private static String keytool() {
        String javaHome = System.getProperty("java.home");
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool";
        Path path = Paths.get(javaHome, "bin", executable);

        return Files.exists(path) ? path.toString() : "keytool";
    }
}
