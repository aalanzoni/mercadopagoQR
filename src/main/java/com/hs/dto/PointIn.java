package com.hs.dto;

/**
 * Datos de entrada para crear un payment intent en MP Point.
 *
 * Campos requeridos en acción O (createPaymentIntent):
 *   - externalReference  : referencia externa única de la operación
 *   - deviceId           : ID del dispositivo Point físico
 *   - totalAmount        : importe como string "99999.99"
 *   - description        : descripción visible en el dispositivo
 *
 * Campos opcionales:
 *   - idempotencyKey     : si viene vacío se usa externalReference
 */
public class PointIn {
    public String externalReference;
    public String deviceId;
    public String totalAmount;
    public String description;
    public String idempotencyKey;
}
