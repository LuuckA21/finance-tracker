package me.luucka.finance.report;

import me.luucka.finance.auth.AppPrincipal;
import me.luucka.finance.common.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class AnnualReportController {

    private final AnnualReportService service;

    public AnnualReportController(AnnualReportService service) {
        this.service = service;
    }

    /** Summary of one calendar year (up to today for the current one), compared with the year before. */
    @GetMapping("/annual")
    public AnnualReportService.AnnualReport annual(@AuthenticationPrincipal AppPrincipal me, @RequestParam int year) {
        if (year < 1900 || year > 2200) {
            throw ApiException.badRequest("invalid_year", "Year out of range");
        }
        return service.annual(me.id(), year);
    }
}
