package dev.tkachuk.bank.account;

import dev.tkachuk.bank.api.AccountsApi;
import dev.tkachuk.bank.api.model.AccountResponse;
import dev.tkachuk.bank.api.model.Currency;
import dev.tkachuk.bank.api.model.DepositRequest;
import dev.tkachuk.bank.api.model.OpenAccountRequest;
import dev.tkachuk.bank.api.model.StatementResponse;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AccountsController implements AccountsApi {

    private static final String DEFAULT_DEPOSIT_DESCRIPTION = "Cash deposit";

    private final AccountService accountService;
    private final AccountMapper mapper;

    @Override
    public ResponseEntity<List<AccountResponse>> listAccounts() {
        return ResponseEntity.ok(mapper.toResponses(accountService.list()));
    }

    @Override
    public ResponseEntity<AccountResponse> openAccount(OpenAccountRequest request) {
        Account account = accountService.open(request.getIban(), request.getHolderName(),
                request.getCurrency().getValue());
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + account.getIban()))
                .body(mapper.toResponse(account));
    }

    @Override
    public ResponseEntity<AccountResponse> getAccount(String iban) {
        return ResponseEntity.ok(mapper.toResponse(accountService.get(iban)));
    }

    @Override
    public ResponseEntity<AccountResponse> depositToAccount(String iban, DepositRequest request) {
        String description = request.getDescription() == null || request.getDescription().isBlank()
                ? DEFAULT_DEPOSIT_DESCRIPTION
                : request.getDescription().strip();
        return ResponseEntity.ok(mapper.toResponse(accountService.deposit(iban, request.getAmount(), description)));
    }

    @Override
    public ResponseEntity<StatementResponse> getAccountStatement(String iban) {
        Account account = accountService.get(iban);
        StatementResponse statement = new StatementResponse()
                .iban(account.getIban())
                .currency(Currency.fromValue(account.getCurrency()))
                .balance(account.getBalance())
                .entries(mapper.toStatementEntries(accountService.statement(iban)));
        return ResponseEntity.ok(statement);
    }
}
