package com.hs;

import java.io.*;
import java.util.Properties;

public class MpConfig {

    private final Properties p;

    private MpConfig(Properties p) {
        this.p = p;
    }

    /**
     * Carga properties desde: 1) -Dmp.config=RUTA\mercadopago.properties 2)
     * ./mercadopago.properties (directorio actual) 3) classpath
     * (/mercadopago.properties)
     */
    public static MpConfig load() {
        Properties props = new Properties();
        InputStream in = null;

        try {
            // 1) Ruta explícita por VM option
            String explicitPath = System.getProperty("mp.config");
            if (explicitPath != null && !explicitPath.trim().isEmpty()) {
                in = new FileInputStream(explicitPath.trim());
            }

            // 2) Archivo en working dir
            if (in == null) {
                File f = new File("mercadopagoQR.properties");
                if (f.exists()) {
                    in = new FileInputStream(f);
                }
            }

            // 3) Classpath (jar / ejecución normal)
            if (in == null) {
                in = MpConfig.class.getResourceAsStream("/mercadopagoQR.properties");
            }

            // 4) Fallback: target/classes (NetBeans + exec-maven-plugin)
            if (in == null) {
                File f = new File("target/classes/mercadopagoQR.properties");
                if (f.exists()) {
                    in = new FileInputStream(f);
                }
            }

            if (in == null) {
                throw new RuntimeException("No se encontró mercadopagoQR.properties por ningún medio.");
            }

            props.load(in);
            return new MpConfig(props);

        } catch (Exception e) {
            throw new RuntimeException("Error cargando mercadopagoQR.properties: " + e.getMessage(), e);
        } finally {
            if (in != null) try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    public String etapa() {
        return get("mp.etapa", "test").trim().toLowerCase();
    }

    public boolean isTest() {
        return "test".equals(etapa());
    }

    public String accessToken() {
        String key = isTest() ? "mp.accessTokenTest" : "mp.accessToken";
        String t = get(key, "").trim();
        if (t.isEmpty()) {
            throw new IllegalStateException("Falta " + key + " en mercadopago.properties");
        }
        return t;
    }

    public String publicKey() {
        String key = isTest() ? "mp.publicKeyTest" : "mp.publicKey";
        String v = get(key, "").trim();
        if (v.isEmpty()) {
            throw new IllegalStateException("Falta " + key + " en mercadopago.properties");
        }
        return v;
    }

    public String userId() {
        String key = isTest() ? "mp.userIdTest" : "mp.userId";
        String v = get(key, "").trim();
        if (v.isEmpty()) {
            throw new IllegalStateException("Falta " + key + " en mercadopago.properties");
        }
        return v;
    }

    public String baseUrl() {
        return get("mp.baseUrl", "https://api.mercadopago.com");
    }

    public int connectTimeoutMs() {
        return getInt("mp.timeout.connect", 10000);
    }

    public int socketTimeoutMs() {
        return getInt("mp.timeout.socket", 20000);
    }

    public String get(String key, String def) {
        String v = p.getProperty(key);
        return (v == null) ? def : v;
    }

    public int getInt(String key, int def) {
        try {
            String v = p.getProperty(key);
            if (v == null || v.trim().isEmpty()) {
                return def;
            }
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
