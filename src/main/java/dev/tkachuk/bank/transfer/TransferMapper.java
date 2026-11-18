package dev.tkachuk.bank.transfer;

import dev.tkachuk.bank.api.model.TransferResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.mapstruct.Mapper;

@Mapper
public interface TransferMapper {

    TransferResponse toResponse(Transfer transfer);

    default OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
