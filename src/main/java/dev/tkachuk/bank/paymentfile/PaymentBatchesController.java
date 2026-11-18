package dev.tkachuk.bank.paymentfile;

import dev.tkachuk.bank.api.PaymentBatchesApi;
import dev.tkachuk.bank.api.model.PaymentBatchItemResult;
import dev.tkachuk.bank.api.model.PaymentBatchResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
public class PaymentBatchesController implements PaymentBatchesApi {

    private final PaymentBatchService paymentBatchService;

    @Override
    public ResponseEntity<PaymentBatchResult> uploadPaymentBatch(MultipartFile file) {
        PaymentBatchReport report;
        try (InputStream content = file.getInputStream()) {
            report = paymentBatchService.process(file.getOriginalFilename(), file.getContentType(), content);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded file " + file.getOriginalFilename(), e);
        }
        return ResponseEntity.ok(toResult(report));
    }

    private static PaymentBatchResult toResult(PaymentBatchReport report) {
        List<PaymentBatchItemResult> items = report.items().stream()
                .map(PaymentBatchesController::toItemResult)
                .toList();
        return new PaymentBatchResult()
                .messageId(report.messageId())
                .format(PaymentBatchResult.FormatEnum.valueOf(report.format().name()))
                .total(report.items().size())
                .accepted(report.acceptedCount())
                .rejected(report.rejectedCount())
                .items(items);
    }

    private static PaymentBatchItemResult toItemResult(PaymentItemOutcome outcome) {
        return new PaymentBatchItemResult()
                .endToEndId(outcome.endToEndId())
                .status(PaymentBatchItemResult.StatusEnum.valueOf(outcome.status().name()))
                .transferId(outcome.transferId())
                .replayed(outcome.replayed())
                .errorCode(outcome.errorCode() == null ? null : outcome.errorCode().name())
                .reason(outcome.reason());
    }
}
