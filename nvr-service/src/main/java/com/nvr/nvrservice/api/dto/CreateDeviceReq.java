package com.nvr.nvrservice.api.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CreateDeviceReq {
    @NotBlank @Size(max = 255) private String name;

    // Храним как строку; в БД у нас columnDefinition=inet
    @NotBlank private String ip;

    @Min(1) @Max(65535) private int port = 554;

    @Min(1) @Max(65535)
    private Integer httpPort; // HTTP порт для API запросов (обычно 80 или 8080-8082)

    @Size(max = 512) private String address;
    @Size(max = 64)  private String vendor;

    @Size(max = 64)
    private String timezone;

    @Min(0)
    private Integer camerasCount;

    // id адреса, к которому нужно привязать устройство (опционально, можно добавить позже)
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

    // ДЕЛАЕМ НЕОБЯЗАТЕЛЬНЫМ: без @NotNull/@Size(min=1)
    @Valid
    @JsonSetter(nulls = Nulls.AS_EMPTY)                      // null -> []
    private List<UserCred> users = new ArrayList<>();        // по умолчанию []

    @Data
    public static class UserCred {
        @NotBlank private String role;
        @NotBlank private String username;
        @NotBlank private String password; // придёт в явном виде -> шифруем и сохраняем
    }
}
