package com.nvr.nvrservice.api.dto;

public record CreateAddressRequest(
        String label,
        String city,
        String street,
        String house,
        String apartment,
        String comment
) {}