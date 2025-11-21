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

    @Size(max = 512) private String address;
    @Size(max = 64)  private String vendor;

    @Size(max = 64)
    private String timezone;

    @Min(0)
    private Integer camerasCount;

    // id адреса, к которому нужно привязать устройство
    @NotNull
    private Long addressId;

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
