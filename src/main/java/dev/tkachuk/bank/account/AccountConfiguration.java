package dev.tkachuk.bank.account;

import dev.tkachuk.bank.common.BankProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AccountConfiguration {

    /** Runs after Flyway, which has created the accounts this reads: repositories wait for the migrations. */
    @Bean
    InternalAccounts internalAccounts(AccountRepository accounts, BankProperties bank) {
        return InternalAccounts.load(accounts, bank);
    }
}
