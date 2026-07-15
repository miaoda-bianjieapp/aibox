package com.aibox.api;

import com.aibox.platform.identity.AccountSummaryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    private final AccountSummaryService summaryService;

    public AccountController(AccountSummaryService summaryService) {
        this.summaryService = summaryService;
    }

    @GetMapping("/summary")
    public AccountSummaryService.AccountSummary summary() {
        return summaryService.get();
    }
}
