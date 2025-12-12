package com.nvr.nvrservice.api.dto;

import lombok.Data;

@Data
public class UpdateDeviceReq {
    private String name;
    private String ip;
    private Integer port;
    private Integer httpPort; // HTTP порт для API запросов
    private String address;
    private String vendor;
    private String timezone;
    private Integer camerasCount;
    private Long addressId;
    
    // Поддержка ввода 6-значного ID как строки ("000001") или числа (1)
    @com.fasterxml.jackson.annotation.JsonSetter("addressId")
    public void setAddressId(Object addressId) {
        if (addressId == null) {
            this.addressId = null;
        } else if (addressId instanceof Long) {
            this.addressId = (Long) addressId;
        } else if (addressId instanceof Number) {
            this.addressId = ((Number) addressId).longValue();
        } else if (addressId instanceof String) {
            // Преобразуем строку "000001" в число 1
            String str = ((String) addressId).trim();
            if (str.isEmpty()) {
                this.addressId = null;
            } else {
                try {
                    this.addressId = Long.parseLong(str);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid addressId format: " + str);
                }
            }
        } else {
            throw new IllegalArgumentException("addressId must be a number or string, got: " + addressId.getClass());
        }
    }
}
