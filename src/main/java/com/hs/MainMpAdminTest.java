package com.hs;

import com.hs.config.MpConfig;
import com.hs.core.MpBridgeCore;
import com.hs.dto.MpResult;
import com.hs.dto.PosIn;
import com.hs.dto.SearchIn;
import com.hs.dto.StoreIn;
import com.hs.http.MpHttpClient;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Test manual por consola de métodos de MpBridgeCore (Admin: S/LS/P/LP).
 *
 * Uso: java ... com.hs.MainMpAdminTest C:\ruta\mercadopagoQR.properties
 */
public class MainMpAdminTest {

    public static void main(String[] args) {
        try {
            // 1) Properties: por args[0] o por -Dmp.config
            if (args != null && args.length > 0 && args[0] != null && !args[0].trim().isEmpty()) {
                System.setProperty("mp.config", args[0].trim());
            }

            MpConfig cfg = MpConfig.load();
            MpHttpClient httpClient = new MpHttpClient(cfg);
            MpBridgeCore core = new MpBridgeCore(cfg, Logger.getLogger("MP_ADMIN_TEST"));

            // ====== Datos de prueba (cambiá a gusto) ======
            String storeExternalId = "SUCURSAL999";
            String storeName = "Sucursal 999";
            String posExternalId = "SUCURSAL999PDV001";
            String posName = "Caja 999";

            // ====== 1) CREATE_STORE ======
            StoreIn s = new StoreIn();
            s.name = storeName;
            s.externalId = storeExternalId;
            s.street = "Av Siempre Viva";
            s.streetNumber = "123";
            s.city = "Córdoba";
            s.state = "Córdoba";
            s.latitude = "-31.4167";
            s.longitude = "-64.1833";
            s.idempotencyKey = "STORE-" + UUID.randomUUID();

            System.out.println("===== CREATE_STORE =====");
            MpResult rs = core.createStore(s);
            dump(rs);

            if (rs.res != 0) {
                System.out.println("ERROR creando store. Corto la prueba.");
                return;
            }

            long storeId = parseLongSafe(rs.id);
            if (storeId <= 0) {
                System.out.println("No pude extraer store_id del response. Corto la prueba.");
                return;
            }

            // ====== 2) SEARCH_STORES ======
            System.out.println("\n===== SEARCH_STORES =====");
            SearchIn ss = new SearchIn();
            ss.limit = 50;
            ss.offset = 0;
            ss.filterExternalId = storeExternalId;
            MpResult rls = core.searchStores(cfg.userId(), ss);
            dump(rls);

            // ====== 3) CREATE_POS ======
            System.out.println("\n===== CREATE_POS =====");
            PosIn p = new PosIn();
            p.name = posName;
            p.externalId = posExternalId;
            p.storeId = storeId;
            p.idempotencyKey = "POS-" + UUID.randomUUID();
            MpResult rp = core.createPos(p);
            dump(rp);

            // ====== 4) SEARCH_POS ======
            System.out.println("\n===== SEARCH_POS =====");
            SearchIn sp = new SearchIn();
            sp.limit = 50;
            sp.offset = 0;
            sp.filterExternalId = posExternalId;
            MpResult rlp = core.searchPos(sp);
            dump(rlp);

            httpClient.closeQuietly();

        } catch (Exception e) {
            System.out.println("ERROR general: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void dump(MpResult r) {
        System.out.println("res=" + r.res);
        System.out.println("msg=" + r.msg);
        System.out.println("id=" + r.id);
        System.out.println("status=" + r.status);
        System.out.println("payment_id=" + r.paymentId);
        if (r.rawJson != null) {
            String preview = r.rawJson.length() > 800 ? r.rawJson.substring(0, 800) + "..." : r.rawJson;
            System.out.println("raw_json=" + preview);
        }
    }

    private static long parseLongSafe(String s) {
        try {
            if (s == null) {
                return 0;
            }
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
