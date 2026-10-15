package me.luucka.finance.search;

import me.luucka.finance.auth.AppPrincipal;
import me.luucka.finance.common.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The search box: the user's records matching a text, grouped by kind. */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService service;

    public SearchController(SearchService service) {
        this.service = service;
    }

    @GetMapping
    public SearchService.Result search(@AuthenticationPrincipal AppPrincipal me, @RequestParam String q) {
        if (q.length() > SearchService.MAX_LENGTH) {
            throw ApiException.badRequest("search_too_long",
                    "Search for at most " + SearchService.MAX_LENGTH + " characters");
        }
        return service.search(me.id(), q);
    }
}
