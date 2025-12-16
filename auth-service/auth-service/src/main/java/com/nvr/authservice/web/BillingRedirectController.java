package com.nvr.authservice.web;

import com.nvr.authservice.repo.PaymentAttemptRepository;
import com.nvr.authservice.service.BillingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Контроллер для landing pages редиректа после оплаты.
 * Tinkoff редиректит на HTTPS страницы, которые затем вызывают диплинк.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class BillingRedirectController {

    private final PaymentAttemptRepository paymentAttemptRepo;
    private final BillingService billingService;

    @GetMapping(value = "/billing/redirect/success", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> successRedirect(@RequestParam(required = false) String paymentId,
                                                  @RequestParam(required = false) String orderId) {
        log.info("Success redirect page accessed: PaymentId={}, OrderId={}", paymentId, orderId);
        
        // Пытаемся автоматически обработать платеж, если webhook не пришел
        // Это безопасно - метод проверяет статус и не создаст дубликат подписки
        try {
            boolean processed = billingService.tryProcessPayment(orderId, paymentId);
            if (processed) {
                log.info("Payment processed from redirect page: PaymentId={}, OrderId={}", paymentId, orderId);
            } else {
                log.warn("Payment could not be processed from redirect page: PaymentId={}, OrderId={}", paymentId, orderId);
            }
        } catch (Exception e) {
            log.error("Error processing payment from redirect page: PaymentId={}, OrderId={}, Error={}", 
                    paymentId, orderId, e.getMessage(), e);
            // Продолжаем показ страницы даже если обработка не удалась
        }

        // Tinkoff может передавать paymentId напрямую, или мы используем orderId из URL
        // Если paymentId передан напрямую - используем его, иначе используем orderId
        String actualPaymentId = paymentId != null ? paymentId : orderId;
        if (actualPaymentId == null) {
            actualPaymentId = "unknown";
        }
        
        String deeplink = "okodoma://payments/success?paymentId=" + escapeHtml(actualPaymentId);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(generateRedirectPage(deeplink, "Успешная оплата", "Оплата прошла успешно!"));
    }

    @GetMapping(value = "/billing/redirect/fail", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> failRedirect(@RequestParam(required = false) String paymentId,
                                               @RequestParam(required = false) String orderId) {
        // Tinkoff может передавать paymentId напрямую, или мы используем orderId из URL
        // Если paymentId передан напрямую - используем его, иначе используем orderId
        String actualPaymentId = paymentId != null ? paymentId : orderId;
        if (actualPaymentId == null) {
            actualPaymentId = "unknown";
        }
        
        String deeplink = "okodoma://payments/fail?paymentId=" + escapeHtml(actualPaymentId);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(generateRedirectPage(deeplink, "Ошибка оплаты", "Оплата не была завершена."));
    }

    private String generateRedirectPage(String deeplink, String title, String message) {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>%s</title>
                    <style>
                        body {
                            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
                            display: flex;
                            justify-content: center;
                            align-items: center;
                            min-height: 100vh;
                            margin: 0;
                            background: linear-gradient(135deg, #667eea 0%%, #764ba2 100%%);
                        }
                        .container {
                            text-align: center;
                            background: white;
                            padding: 40px;
                            border-radius: 16px;
                            box-shadow: 0 10px 40px rgba(0,0,0,0.2);
                            max-width: 400px;
                        }
                        h1 {
                            color: #333;
                            margin: 0 0 16px 0;
                            font-size: 24px;
                        }
                        p {
                            color: #666;
                            margin: 0 0 24px 0;
                            font-size: 16px;
                        }
                        #link {
                            display: inline-block;
                            padding: 12px 24px;
                            background: #667eea;
                            color: white;
                            text-decoration: none;
                            border-radius: 8px;
                            font-weight: 500;
                            transition: background 0.3s;
                        }
                        #link:hover {
                            background: #5568d3;
                        }
                        #wrap {
                            display: none;
                        }
                        .spinner {
                            border: 3px solid #f3f3f3;
                            border-top: 3px solid #667eea;
                            border-radius: 50%%;
                            width: 40px;
                            height: 40px;
                            animation: spin 1s linear infinite;
                            margin: 0 auto 20px;
                        }
                        @keyframes spin {
                            0%% { transform: rotate(0deg); }
                            100%% { transform: rotate(360deg); }
                        }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div id="spinner" class="spinner"></div>
                        <div id="wrap">
                            <h1>%s</h1>
                            <p>%s</p>
                            <a href="%s" id="link">Открыть в приложении</a>
                        </div>
                    </div>
                    <script>
                        const deep = "%s";
                        window.location.href = deep;
                        setTimeout(() => {
                            document.getElementById("spinner").style.display = "none";
                            document.getElementById("link").href = deep;
                            document.getElementById("wrap").style.display = "block";
                        }, 500);
                    </script>
                </body>
                </html>
                """.formatted(title, title, message, deeplink, deeplink);
    }

    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}

