// com.nvr.nvrservice.security.UserContext.java
package com.nvr.nvrservice.security;

public record UserContext(Long userId, String plan, Integer maxCameras, Integer archiveDays) {}
