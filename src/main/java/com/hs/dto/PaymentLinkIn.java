package com.hs.dto;

import java.util.List;

public class PaymentLinkIn {
    public String externalReference;

    public String payerName;
    public String payerEmail;

    public String notificationUrl;
    public String backUrlSuccess;
    public String backUrlPending;
    public String backUrlFailure;

    public String expirationDateFrom;
    public String expirationDateTo;

    public String idempotencyKey;
    
    public String additionalInfo;

    public List<PaymentLinkItemIn> items;
}