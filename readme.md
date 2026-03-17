# Integración Mercado Pago QR Híbrido (Java + ISCobol)

## 📌 Descripción

Este proyecto implementa la integración con Mercado Pago para pagos mediante **QR híbrido (presencial)**, permitiendo:

- Crear órdenes QR
- Consultar órdenes
- Cancelar órdenes
- Realizar devoluciones (refund)
- Consultar pagos
- Administrar Stores (sucursales)
- Administrar POS (cajas)
- Buscar Stores y POS

Está diseñado para ser consumido desde **ISCobol** mediante un puente Java.

---

## 🧱 Arquitectura

### Componentes principales

- **MP_QR_HIBRIDO**
  - Clase puente ISCobol (`IscobolCall`)
  - Punto de entrada principal

- **MpBridgeCore**
  - Lógica de negocio principal

- **MpHttpClient / MpHttpAdapter**
  - Manejo de llamadas HTTP a Mercado Pago

- **MpOutWriter**
  - Escritura de salidas hacia el array `argv[]`

- **MpConfig**
  - Configuración del sistema (properties)

---

## ⚠️ Clase LEGACY

```
com.hs.MpQrService
```

❌ **NO UTILIZAR**

Esta clase quedó obsoleta y fue reemplazada por:

- `MpBridgeCore`
- `MP_QR_HIBRIDO`

---

## ⚙️ Configuración

Archivo:

```
src/main/resources/mercadopagoQR.properties
```

También se puede definir por parámetro:

```
-Dmp.config=RUTA\mercadopagoQR.properties
```

### Ejemplo mínimo

```properties
mp.env=TEST
mp.access.token=TEST-XXXXXXXX
mp.user.id=123456

mp.endpoint.createOrder=/v1/orders
mp.endpoint.getOrder=/v1/orders/%s
mp.endpoint.cancelOrder=/v1/orders/%s/cancel
mp.endpoint.refundOrder=/v1/orders/%s/refund
mp.endpoint.getPayment=/v1/payments/%s
```

---

## 🔢 CONTRATO ISCOBOL (argv)

### Total: 36 posiciones (0..35)

---

### 🔹 ENTRADAS (0..25)

| Pos | Nombre |
|-----|--------|
| 0   | ACCION |
| 1   | EXT_REF / ID |
| 2   | DESCRIPCION |
| 3   | EXT_POS_ID |
| 4   | MODO |
| 5   | EXPIRACION |
| 6   | TOTAL |
| 7   | UNIT_PRICE |
| 8   | ITEM_ID |
| 9   | EXTERNAL_CODE |
| 10  | IDEMPOTENCY_KEY |
| 11  | STORE_EXTERNAL_ID |
| 12  | STORE_NAME |
| 13  | STORE_ADDRESS |
| 14  | POS_EXTERNAL_ID |
| 15  | POS_NAME |
| 16  | POS_CATEGORY |
| 17  | LIMIT |
| 18  | OFFSET |
| 19  | FILTER_EXTERNAL_ID |
| 20-25 | (reservado) |

---

### 🔹 SALIDAS (26..35)

| Pos | Nombre |
|-----|--------|
| 26  | RESULTADO |
| 27  | MENSAJE |
| 28  | ID |
| 29  | QR_DATA |
| 30  | STATUS |
| 31  | PAYMENT_ID |
| 32  | RAW_JSON |
| 33  | QR_IMAGE |
| 34  | QR_TEMPLATE_DOCUMENT |
| 35  | QR_TEMPLATE_IMAGE |

---

## 🔄 ACCIONES SOPORTADAS

| Código | Acción |
|--------|--------|
| O  | Crear orden QR |
| Q  | Consultar orden |
| C  | Cancelar orden |
| R  | Refund |
| QP | Consultar pago |
| S  | Crear Store |
| P  | Crear POS |
| LS | Listar Stores |
| LP | Listar POS |

---

## 📥 Uso de campos por acción

### O (Crear orden)
Usa:
- EXT_REF
- DESCRIPCION
- EXT_POS_ID
- MODO
- EXPIRACION
- TOTAL
- UNIT_PRICE
- ITEM_ID
- EXTERNAL_CODE
- IDEMPOTENCY_KEY

---

### Q / C / R
- EXT_REF = order_id

---

### QP
- EXT_REF = payment_id

---

### S (Store)
- STORE_EXTERNAL_ID
- STORE_NAME
- STORE_ADDRESS

---

### P (POS)
- POS_EXTERNAL_ID
- POS_NAME
- POS_CATEGORY
- STORE_EXTERNAL_ID

---

### LS / LP
- LIMIT
- OFFSET
- FILTER_EXTERNAL_ID

---

## 🛠️ Build

Requisitos:

- JDK 1.8
- Maven
- ISCobol runtime (`iscobol.jar`)

Compilar:

```bash
mvn clean package
```

---

## ⚠️ Consideraciones

- El contrato ISCobol debe respetarse EXACTAMENTE (posiciones fijas)
- No mezclar versiones de contrato (históricamente hubo cambios)
- `external_reference` es clave para trazabilidad
- Se recomienda usar idempotency key en operaciones críticas

---

## 🔐 Seguridad

- NO versionar tokens reales
- Usar archivo `.properties` externo en producción
- Configurar correctamente HTTPS

---

## 🚀 Próximos pasos sugeridos

- Integración con **Payment Links (Checkout Pro)**
- Implementar **Webhooks**
- Persistencia local de operaciones
- Auditoría de transacciones

---

## 📎 Notas

Este proyecto está preparado para integrarse con sistemas legacy ISCobol manteniendo compatibilidad hacia atrás, pero se recomienda avanzar hacia una arquitectura modular para futuras integraciones.
