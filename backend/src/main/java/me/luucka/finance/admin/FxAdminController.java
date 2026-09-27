package me.luucka.finance.admin;

import me.luucka.finance.common.ApiException;
import me.luucka.finance.fx.CentralRateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * State of the ECB rate download and manual refresh. Access restricted to ROLE_ADMIN in
 * {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/admin/fx")
public class FxAdminController {

    private static final Logger log = LoggerFactory.getLogger(FxAdminController.class);

    private final CentralRateService central;

    public FxAdminController(CentralRateService central) {
        this.central = central;
    }

    @GetMapping
    public CentralRateService.Status status() {
        return central.status();
    }

    /** Downloads now, even when the rates look up to date or automatic updates are off. */
    @PostMapping("/refresh")
    public CentralRateService.RefreshResult refresh() {
        if (central.status().historyRunning()) {
            throw busy();
        }
        try {
            return central.refresh(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception e) {
            log.warn("Manual ECB refresh failed: {}", e.toString());
            throw unavailable();
        }
    }

    /** Starts downloading the full history again; progress and errors show up in the status. */
    @PostMapping("/history")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CentralRateService.Status downloadHistory() {
        if (!central.startHistoryDownload()) {
            throw busy();
        }
        return central.status();
    }

    private static ApiException busy() {
        return ApiException.conflict("ecb_busy", "The ECB history is already being downloaded");
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "ecb_unavailable",
                "The ECB rates could not be downloaded; see the status for details");
    }
}
