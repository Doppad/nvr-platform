package com.nvr.nvrservice.api.dto;

public record AddressDto(
        Long id,
        String label,
        String city,
        String street,
        String house,
        String apartment,
        String comment
) {}