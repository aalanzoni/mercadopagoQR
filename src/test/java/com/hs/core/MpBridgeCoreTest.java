package com.hs.core;

import com.hs.config.MpConfig;
import com.hs.dto.*;
import com.hs.http.MpHttp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitarios del core sin hacer llamadas reales a MP (se mockea MpHttp).
 */
public class MpBridgeCoreTest {

    @Test
    void createOrder_buildsAndParsesResponse() throws Exception {
        MpConfig cfg = mock(MpConfig.class);
        when(cfg.get(eq("mp.endpoint.createOrder"), anyString())).thenReturn("/v1/orders");

        MpHttp http = mock(MpHttp.class);

        String mpResp = "{\"id\":\"123\",\"status\":\"created\",\"type_response\":{\"qr_data\":\"000201...\"},\"transactions\":{\"payments\":[{\"id\":\"p1\"}]}}";
        when(http.postJson(eq("/v1/orders"), anyString(), anyString()))
                .thenReturn(new MpHttp.MpHttpResponse(201, mpResp));

        MpBridgeCore core = new MpBridgeCore(cfg, http);

        OrderIn in = new OrderIn();
        in.externalReference = "REF-1";
        in.externalPosId = "POS-EXT-1";
        in.totalAmount = "54.00";
        in.description = "Test";
        in.mode = "dynamic";
        in.unitMeasure = "unidad";
        in.itemTitle = "Item";
        in.externalCode = "X";
        in.idempotencyKey = "IDEM-1";

        MpResult r = core.createOrder(in);

        assertEquals(0, r.res);
        assertEquals("123", r.id);
        assertEquals("created", r.status);
        assertEquals("p1", r.paymentId);
        assertNotNull(r.qrData);
        assertTrue(r.qrData.startsWith("000201"));

        // Verifica que se llamó con idempotency
        verify(http, times(1)).postJson(eq("/v1/orders"), anyString(), eq("IDEM-1"));
    }

    @Test
    void cancelOrder_returnsBusinessWhenNotCreated() throws Exception {
        MpConfig cfg = mock(MpConfig.class);
        when(cfg.get(eq("mp.endpoint.getOrder"), anyString())).thenReturn("/v1/orders/%s");
        when(cfg.get(eq("mp.endpoint.cancelOrder"), anyString())).thenReturn("/v1/orders/%s/cancel");

        MpHttp http = mock(MpHttp.class);
        when(http.get(eq("/v1/orders/123")))
                .thenReturn(new MpHttp.MpHttpResponse(200, "{\"status\":\"processing\"}"));

        MpBridgeCore core = new MpBridgeCore(cfg, http);
        MpResult r = core.cancelOrder("123", "IDEM");

        assertEquals(2, r.res);
        assertTrue(r.msg.toLowerCase().contains("no se puede cancelar"));
        verify(http, never()).postJson(anyString(), anyString(), anyString());
    }

    @Test
    void searchPos_buildsQueryWithExternalId() throws Exception {
        MpConfig cfg = mock(MpConfig.class);
        when(cfg.get(eq("mp.endpoint.searchPos"), anyString())).thenReturn("/pos");

        MpHttp http = mock(MpHttp.class);
        when(http.get(contains("/pos?")))
                .thenReturn(new MpHttp.MpHttpResponse(200, "{\"results\":[]}"));

        MpBridgeCore core = new MpBridgeCore(cfg, http);
        SearchIn in = new SearchIn();
        in.limit = 50;
        in.offset = 0;
        in.filterExternalId = "POS-EXT";

        MpResult r = core.searchPos(in);
        assertEquals(0, r.res);
        verify(http, times(1)).get(contains("external_id="));
    }
}
