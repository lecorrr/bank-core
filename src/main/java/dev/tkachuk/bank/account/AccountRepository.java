package dev.tkachuk.bank.account;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByIban(String iban);

    boolean existsByIban(String iban);

    List<Account> findAllByKindOrderByIdAsc(AccountKind kind);

    /** One of the bank's own accounts: there is exactly one of each internal kind. */
    Optional<Account> findByKind(AccountKind kind);

    /**
     * Loads the account and locks its row (SELECT ... FOR UPDATE) until the transaction ends.
     * Another transaction asking for the same row waits, so two transfers cannot both spend the same money.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.iban = :iban")
    Optional<Account> findByIbanForUpdate(@Param("iban") String iban);
}
