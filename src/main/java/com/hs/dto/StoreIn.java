package com.hs.dto;

/** Campos para CREATE_STORE (acción S). */
public class StoreIn {
    public String name;
    public String externalId;
    public String street;
    public String streetNumber; // MP lo exige como string
    public String city;
    public String state;
    public String latitude;     // string -> parse double
    public String longitude;    // string -> parse double
    public String idempotencyKey;
}
