package dev.tkachuk.bank.account;

import dev.tkachuk.bank.common.BankProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AccountConfiguration {

    @Bean
    // Runs after Flyway, which creates the internal accounts.
    InternalAccounts internalAccounts(AccountRepository accounts, BankProperties bank) {
        return InternalAccounts.load(accounts, bank);
    }
}
