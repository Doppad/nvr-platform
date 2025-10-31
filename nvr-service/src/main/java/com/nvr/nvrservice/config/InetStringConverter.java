package com.nvr.nvrservice.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.postgresql.util.PGobject;

@Converter(autoApply = false)
public class InetStringConverter implements AttributeConverter<String, Object> {

    @Override
    public Object convertToDatabaseColumn(String ip) {
        if (ip == null || ip.isBlank()) return null;
        try {
            PGobject obj = new PGobject();
            obj.setType("inet");
            obj.setValue(ip);
            return obj;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid inet: " + ip, e);
        }
    }

    @Override
    public String convertToEntityAttribute(Object dbData) {
        return dbData == null ? null : dbData.toString();
    }
}
