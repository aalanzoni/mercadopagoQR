package com.hs;

import com.hs.config.MpConfig;
import com.hs.core.MpBridgeCore;
import com.hs.dto.MpResult;
import com.hs.dto.OrderIn;
import com.hs.dto.PaymentLinkIn;
import com.hs.dto.PaymentLinkItemIn;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Test manual por consola de métodos de MpBridgeCore (Admin: S/LS/P/LP).
 *
 * Uso: java ... com.hs.MainMpAdminTest C:\ruta\mercadopagoQR.properties
 */
public class MainMpAdminTest {


    // -----------------------------------------------------------------------
    // Ejecutar este main para reproducir el bug de cancel 400.
    // Pasa la ruta al .properties como args[0] o como -Dmp.config=<ruta>.
    // -----------------------------------------------------------------------
    public static void main(String[] args) {
        if (args != null && args.length > 0 && args[0] != null && !args[0].trim().isEmpty()) {
            System.setProperty("mp.config", args[0].trim());
        }
        testCancelOrder();
    }

    private static void testCancelOrder() {
        try {
            MpConfig cfg = MpConfig.load();
            MpBridgeCore core = new MpBridgeCore(cfg, Logger.getLogger("MP_CANCEL_TEST"));

            String extRef = "TEST-CANCEL-" + System.currentTimeMillis();
            String idem   = "IDEM-" + extRef;

            // 1) Crear orden
            System.out.println("===== CREATE_ORDER =====");
            OrderIn in = new OrderIn();
            in.externalReference = extRef;
            in.description       = "Orden de prueba cancel";
            in.externalPosId     = "caja01ofhs";
            in.mode              = "dynamic";
            in.totalAmount       = "100.00";
            in.unitMeasure       = "unit";
            in.itemTitle         = "Item test";
            in.externalCode      = "ITEM-TEST";
            in.idempotencyKey    = idem;
            MpResult rCreate = core.createOrder(in);
            dump(rCreate);

            if (rCreate.res != 0) {
                System.out.println("ERROR creando orden. Se corta la prueba.");
                return;
            }

            String orderId = rCreate.id;
            System.out.println("order_id=" + orderId);

            // 2) Consultar estado inmediatamente
            System.out.println("\n===== GET_ORDER (antes de cancelar) =====");
            MpResult rGet1 = core.getOrder(orderId);
            dump(rGet1);

            // 3) Cancelar
            System.out.println("\n===== CANCEL_ORDER =====");
            MpResult rCancel = core.cancelOrder(orderId, "CANCEL-" + idem);
            dump(rCancel);

            // 4) Consultar estado después de cancelar (ver si MP lo marcó cancelled igual)
            System.out.println("\n===== GET_ORDER (después de cancelar) =====");
            MpResult rGet2 = core.getOrder(orderId);
            dump(rGet2);

        } catch (Exception e) {
            System.out.println("ERROR general: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unused")
    private static void testPaymentLink() {
        try {
            MpConfig cfg = MpConfig.load();
//            MpBridgeCore core = new MpBridgeCore(cfg);
            MpBridgeCore core = new MpBridgeCore(cfg, Logger.getLogger("MP_ADMIN_TEST"));
            PaymentLinkIn in = new PaymentLinkIn();
            in.externalReference = "COB-TEST-20260519-0004";
            in.payerName = "Socio de Prueba";
            in.payerEmail = "aalanzoni@gmail.com";
            in.notificationUrl = "";
            in.backUrlSuccess = "";
            in.backUrlPending = "";
            in.backUrlFailure = "";
            in.expirationDateFrom = "";
            in.expirationDateTo = "";
            in.idempotencyKey = "IDEMP-COB-TEST-20260519-0004";

            List<PaymentLinkItemIn> items = new ArrayList<PaymentLinkItemIn>();

            PaymentLinkItemIn i1 = new PaymentLinkItemIn();
            i1.code = "CUOTA-2026-01";
            i1.title = "Cuota enero 2026";
            i1.description = "Servicio social - período 01/2026";
            i1.quantity = "1";
            i1.unitPrice = "10500.00";
            items.add(i1);

            PaymentLinkItemIn i2 = new PaymentLinkItemIn();
            i2.code = "CUOTA-2026-02";
            i2.title = "Cuota febrero 2026";
            i2.description = "Servicio social - período 02/2026";
            i2.quantity = "1";
            i2.unitPrice = "10250.00";
            items.add(i2);

            in.items = items;

            MpResult r = core.createPaymentLink(in);

            dump(r);

        } catch (Exception e) {
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
