package com.hs;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.UUID;

public class MainMpAdminTest {

    private static final Gson GSON = new Gson();

    public static void main(String[] args) {

        try {
            // 1) Properties: por args[0] o por -Dmp.config
            if (args != null && args.length > 0 && args[0] != null && !args[0].trim().isEmpty()) {
                System.setProperty("mp.config", args[0].trim());
            }

            MpConfig cfg = MpConfig.load();
            MpHttpClient http = new MpHttpClient(cfg);

            // ====== Datos de prueba (cambiá a gusto) ======
            String storeExternalId = "SUCURSAL999";
            String storeName = "Sucursal 999";
            String posExternalId = "SUCURSAL999PDV001";
            String posName = "Caja 999";

            // ====== 1) CREATE_STORE ======
            String storeReq = buildCreateStoreJson(storeName, storeExternalId);
            String storeIdempotency = "STORE-" + UUID.randomUUID();

            System.out.println("===== CREATE_STORE =====");
            MpHttpClient.MpHttpResponse storeResp = postCreateStore(http, cfg, storeReq, storeIdempotency);
            System.out.println("HTTP " + storeResp.statusCode);
            System.out.println(storeResp.body);

            if (!storeResp.is2xx()) {
                System.out.println("ERROR creando store. Corto la prueba.");
                return;
            }

            String storeId = extractField(storeResp.body, "id");
            if (storeId == null || storeId.trim().isEmpty()) {
                System.out.println("No pude extraer store_id del response. Corto la prueba.");
                return;
            }
            System.out.println("STORE_ID=" + storeId);

            // ====== 2) SEARCH_STORES (traer todas las sucursales, primera página) ======
            // Podés ajustar limit/offset.
            String storesQuery = "limit=50&offset=0";

            System.out.println("\n===== SEARCH_STORES =====");
            MpHttpClient.MpHttpResponse searchStoresResp = getSearchStores(http, cfg, storesQuery);
            System.out.println("HTTP " + searchStoresResp.statusCode);
            System.out.println(searchStoresResp.body);

            // ====== 3) CREATE_POS ======
            String posReq = buildCreatePosJson(posName, posExternalId, storeId);
            String posIdempotency = "POS-" + UUID.randomUUID();

            System.out.println("\n===== CREATE_POS =====");
            MpHttpClient.MpHttpResponse posResp = postCreatePos(http, cfg, posReq, posIdempotency);
            System.out.println("HTTP " + posResp.statusCode);
            System.out.println(posResp.body);

            // ====== 4) SEARCH_POS (traer cajas / filtrar por external_id) ======
            String posQuery = "external_id=" + urlEncode(posExternalId) + "&limit=50&offset=0";

            System.out.println("\n===== SEARCH_POS =====");
            MpHttpClient.MpHttpResponse searchPosResp = getSearchPos(http, cfg, posQuery);
            System.out.println("HTTP " + searchPosResp.statusCode);
            System.out.println(searchPosResp.body);

        } catch (Exception e) {
            System.out.println("ERROR general: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ----------------- Calls -----------------

    private static MpHttpClient.MpHttpResponse postCreateStore(MpHttpClient http, MpConfig cfg, String jsonBody, String idempotencyKey) throws Exception {
        String userId = cfg.userId();
        String endpointFmt = cfg.get("mp.endpoint.createStore", "/users/%s/stores");
        String endpoint = String.format(endpointFmt, userId);
        return http.postJson(endpoint, jsonBody, idempotencyKey);
    }

    private static MpHttpClient.MpHttpResponse getSearchStores(MpHttpClient http, MpConfig cfg, String queryString) throws Exception {
        String userId = cfg.userId();
        String endpointFmt = cfg.get("mp.endpoint.searchStores", "/users/%s/stores/search");
        String endpoint = String.format(endpointFmt, userId);
        endpoint = appendQuery(endpoint, queryString);
        return http.get(endpoint, null);
    }

    private static MpHttpClient.MpHttpResponse postCreatePos(MpHttpClient http, MpConfig cfg, String jsonBody, String idempotencyKey) throws Exception {
        String endpoint = cfg.get("mp.endpoint.createPos", "/pos");
        return http.postJson(endpoint, jsonBody, idempotencyKey);
    }

    private static MpHttpClient.MpHttpResponse getSearchPos(MpHttpClient http, MpConfig cfg, String queryString) throws Exception {
        String endpoint = cfg.get("mp.endpoint.searchPos", "/pos");
        endpoint = appendQuery(endpoint, queryString);
        return http.get(endpoint, null);
    }

    // ----------------- JSON builders -----------------

    private static String buildCreateStoreJson(String name, String externalId) {
        // Ejemplo razonable. Ajustá dirección/geo a tu realidad.
        JsonObject root = new JsonObject();
        root.addProperty("name", name);
        root.addProperty("external_id", externalId);

        JsonObject loc = new JsonObject();
        loc.addProperty("street_name", "Av Siempre Viva");
        loc.addProperty("street_number", "123");
        loc.addProperty("city_name", "Córdoba");
        loc.addProperty("state_name", "Córdoba");
        loc.addProperty("latitude", -31.4167);
        loc.addProperty("longitude", -64.1833);

        root.add("location", loc);

        return GSON.toJson(root);
    }

    private static String buildCreatePosJson(String name, String externalId, String storeId) {
        JsonObject root = new JsonObject();
        root.addProperty("name", name);
        root.addProperty("external_id", externalId);

        // store_id suele ser numérico; si viene como string numérica, MP lo acepta normalmente.
        // Si te diera problemas, convertimos a long y usamos addProperty con Number.
        root.addProperty("store_id", storeId);

        return GSON.toJson(root);
    }

    // ----------------- Utils -----------------

    private static String appendQuery(String base, String queryString) {
        if (queryString == null) return base;
        String q = queryString.trim();
        if (q.isEmpty()) return base;
        if (q.startsWith("?")) q = q.substring(1);
        if (q.isEmpty()) return base;
        return base.contains("?") ? (base + "&" + q) : (base + "?" + q);
    }

    private static String extractField(String json, String field) {
        try {
            JsonObject o = GSON.fromJson(json, JsonObject.class);
            if (o != null && o.has(field) && !o.get(field).isJsonNull()) {
                return o.get(field).getAsString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
