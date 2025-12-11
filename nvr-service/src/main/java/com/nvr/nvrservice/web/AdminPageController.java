package com.nvr.nvrservice.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Контроллер для обслуживания статических страниц админки.
 */
@Controller
public class AdminPageController {

    /**
     * Редирект с /admin на /admin/admin.html
     */
    @GetMapping("/admin")
    public String adminRedirect() {
        return "redirect:/admin/admin.html";
    }
}


