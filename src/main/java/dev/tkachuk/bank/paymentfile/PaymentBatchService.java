package dev.tkachuk.bank.paymentfile;

import dev.tkachuk.bank.common.error.BankException;
import dev.tkachuk.bank.common.error.ErrorCode;
import dev.tkachuk.bank.transfer.TransferCommand;
import dev.tkachuk.bank.transfer.TransferResult;
import dev.tkachuk.bank.transfer.TransferService;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

/** Pays a payment file, each payment as its own transfer. Not @Transactional: one failure must not undo the rest. */
@Slf4j
@Service
public class PaymentBatchService {

    private final Map<PaymentFileFormat, PaymentFileReader> readers = new EnumMap<>(PaymentFileFormat.class);
    private final TransferService transferService;

    public PaymentBatchService(List<PaymentFileReader> readerList, TransferService transferService) {
        for (PaymentFileReader reader : readerList) {
            if (readers.put(reader.format(), reader) != null) {
                throw new IllegalStateException("Two payment file readers for " + reader.format());
            }
        }
        for (PaymentFileFormat format : PaymentFileFormat.values()) {
            if (!readers.containsKey(format)) {
                throw new IllegalStateException("No payment file reader for " + format);
            }
        }
        this.transferService = transferService;
    }

    public PaymentBatchReport process(String filename, String contentType, InputStream content) {
        PaymentFileFormat format = PaymentFileFormat.detect(filename, contentType);
        PaymentBatch batch = readers.get(format).read(content);
        log.info("Payment batch {} ({}) with {} payments from {}", batch.messageId(), format,
                batch.payments().size(), batch.debtorIban());
        List<PaymentItemOutcome> outcomes = new ArrayList<>();
        for (PaymentInstruction payment : batch.payments()) {
            outcomes.add(pay(batch, payment));
        }
        PaymentBatchReport report = new PaymentBatchReport(batch.messageId(), format, outcomes);
        log.info("Payment batch {} done: {} accepted, {} rejected", batch.messageId(),
                report.acceptedCount(), report.rejectedCount());
        return report;
    }

    private PaymentItemOutcome pay(PaymentBatch batch, PaymentInstruction payment) {
        // Uploading the same file again pays nothing twice.
        String idempotencyKey = batch.messageId() + ":" + payment.endToEndId();
        String description = payment.remittanceInfo() != null
                ? payment.remittanceInfo()
                : "Payment " + payment.endToEndId();
        TransferCommand command = new TransferCommand(idempotencyKey, batch.debtorIban(), payment.creditorIban(),
                payment.creditorName(), payment.amount(), payment.currency(), description);
        try {
            TransferResult result = transferService.transfer(command);
            return PaymentItemOutcome.ofAccepted(payment.endToEndId(), result.transfer().getId(), result.replayed());
        } catch (BankException e) {
            log.info("Payment {} of batch {} rejected: {}", payment.endToEndId(), batch.messageId(), e.getMessage());
            return PaymentItemOutcome.ofRejected(payment.endToEndId(), e.code(), e.getMessage());
        } catch (DataIntegrityViolationException | PessimisticLockingFailureException e) {
            log.warn("Payment {} of batch {} hit a concurrent change: {}", payment.endToEndId(), batch.messageId(),
                    e.getMessage());
            return PaymentItemOutcome.ofRejected(payment.endToEndId(), ErrorCode.CONFLICT,
                    "Another request was changing the same accounts; upload the file again");
        }
    }
}
