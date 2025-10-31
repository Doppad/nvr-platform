package com.nvr.nvrservice.api.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
public class CreateDeviceReq {
    @NotBlank @Size(max = 255) private String name;

    // Храним как строку; в БД у нас columnDefinition=inet
    @NotBlank private String ip;

    @Min(1) @Max(65535) private int port = 554;

    @Size(max = 512) private String address;
    @Size(max = 64)  private String vendor;

    // user_admin / user_default / user_archive / user_ai
    @NotNull @Size(min = 1) private List<UserCred> users;

    @Data
    public static class UserCred {
        @NotBlank private String role;
        @NotBlank private String username;
        @NotBlank private String password; // придёт в явном виде -> шифруем и сохраняем
    }
}
