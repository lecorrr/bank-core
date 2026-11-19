package dev.tkachuk.bank.account;

import dev.tkachuk.bank.api.model.AccountResponse;
import dev.tkachuk.bank.api.model.StatementEntryResponse;
import dev.tkachuk.bank.ledger.LedgerEntry;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.mapstruct.Mapper;

@Mapper
public interface AccountMapper {

    AccountResponse toResponse(Account account);

    List<AccountResponse> toResponses(List<Account> accounts);

    StatementEntryResponse toStatementEntry(LedgerEntry entry);

    List<StatementEntryResponse> toStatementEntries(List<LedgerEntry> entries);

    default OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
