package com.nvr.nvrservice.api.dto;

public record AddressDto(
        String id,
        String label,
        String city,
        String street,
        String house,
        String apartment,
        String comment
) {}