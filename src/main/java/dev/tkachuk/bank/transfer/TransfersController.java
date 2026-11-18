package dev.tkachuk.bank.transfer;

import dev.tkachuk.bank.api.TransfersApi;
import dev.tkachuk.bank.api.model.TransferRequest;
import dev.tkachuk.bank.api.model.TransferResponse;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TransfersController implements TransfersApi {

    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final TransferService transferService;
    private final TransferMapper mapper;

    @Override
    public ResponseEntity<TransferResponse> createTransfer(String idempotencyKey, TransferRequest request) {
        TransferCommand command = new TransferCommand(idempotencyKey, request.getFromIban(), request.getToIban(),
                request.getToName(), request.getAmount(), request.getCurrency().getValue(), request.getDescription());
        TransferResult result = transferService.transfer(command);
        TransferResponse body = mapper.toResponse(result.transfer());
        if (result.replayed()) {
            return ResponseEntity.ok().header(REPLAYED_HEADER, "true").body(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/transfers/" + body.getId())).body(body);
    }

    @Override
    public ResponseEntity<TransferResponse> getTransfer(UUID transferId) {
        return ResponseEntity.ok(mapper.toResponse(transferService.get(transferId)));
    }
}
