package dev.tkachuk.bank.transfer;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);

    /** The transfer made of a payment from another bank, which is kept under the UETR that bank gave it. */
    Optional<Transfer> findByUetr(UUID uetr);

    /**
     * Loads the transfer and locks its row until the transaction ends, so two answers about the same transfer
     * (a repeated status event, say) are applied one after the other, and the second sees what the first did.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transfer t where t.id = :id")
    Optional<Transfer> findByIdForUpdate(@Param("id") UUID id);
}
