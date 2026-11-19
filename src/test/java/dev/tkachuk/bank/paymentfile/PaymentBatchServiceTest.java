package dev.tkachuk.bank.paymentfile;

import static dev.tkachuk.bank.support.BankErrors.codeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.tkachuk.bank.common.error.BankException;
import dev.tkachuk.bank.common.error.ErrorCode;
import dev.tkachuk.bank.support.TestIbans;
import dev.tkachuk.bank.transfer.Transfer;
import dev.tkachuk.bank.transfer.TransferCommand;
import dev.tkachuk.bank.transfer.TransferResult;
import dev.tkachuk.bank.transfer.TransferService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentBatchServiceTest {

    private static final PaymentBatch BATCH = new PaymentBatch("MSG-1", TestIbans.A, List.of(
            new PaymentInstruction("E2E-1", TestIbans.B, "Olena Kovalenko", new BigDecimal("150.00"), "UAH",
                    "Invoice 17"),
            new PaymentInstruction("E2E-2", TestIbans.C, "Supplier LLC", new BigDecimal("200.50"), "UAH", null)));

    @Mock
    private TransferService transferService;

    private PaymentBatchService service;

    @BeforeEach
    void setUp() {
        service = new PaymentBatchService(List.of(reader(PaymentFileFormat.JSON, BATCH),
                reader(PaymentFileFormat.XML, BATCH)), transferService);
    }

    @Test
    void shouldMakeEachPaymentATransferWithItsOwnKey() {
        when(transferService.transfer(any())).thenAnswer(call -> completed(call.getArgument(0), false));

        PaymentBatchReport report = service.process("batch.json", "application/json", emptyFile());

        ArgumentCaptor<TransferCommand> commands = ArgumentCaptor.forClass(TransferCommand.class);
        verify(transferService, times(2)).transfer(commands.capture());
        assertThat(commands.getAllValues()).containsExactly(
                new TransferCommand("MSG-1:E2E-1", TestIbans.A, TestIbans.B, "Olena Kovalenko",
                        new BigDecimal("150.00"), "UAH", "Invoice 17"),
                new TransferCommand("MSG-1:E2E-2", TestIbans.A, TestIbans.C, "Supplier LLC",
                        new BigDecimal("200.50"), "UAH", "Payment E2E-2"));
        assertThat(report.messageId()).isEqualTo("MSG-1");
        assertThat(report.format()).isEqualTo(PaymentFileFormat.JSON);
        assertThat(report.acceptedCount()).isEqualTo(2);
        assertThat(report.rejectedCount()).isZero();
        assertThat(report.items()).allSatisfy(item -> {
            assertThat(item.status()).isEqualTo(PaymentItemOutcome.Status.ACCEPTED);
            assertThat(item.transferId()).isNotNull();
            assertThat(item.replayed()).isFalse();
        });
    }

    @Test
    void shouldPickReaderByFormat() {
        PaymentBatch xmlBatch = new PaymentBatch("MSG-XML", TestIbans.A, BATCH.payments());
        service = new PaymentBatchService(List.of(reader(PaymentFileFormat.JSON, BATCH),
                reader(PaymentFileFormat.XML, xmlBatch)), transferService);
        when(transferService.transfer(any())).thenAnswer(call -> completed(call.getArgument(0), false));

        PaymentBatchReport report = service.process("batch.xml", null, emptyFile());

        assertThat(report.messageId()).isEqualTo("MSG-XML");
        assertThat(report.format()).isEqualTo(PaymentFileFormat.XML);
    }

    @Test
    void shouldContinueAfterRejectedPayment() {
        when(transferService.transfer(any()))
                .thenThrow(new BankException(ErrorCode.INSUFFICIENT_FUNDS, "Not enough money"))
                .thenAnswer(call -> completed(call.getArgument(0), false));

        PaymentBatchReport report = service.process("batch.json", null, emptyFile());

        assertThat(report.items()).hasSize(2);
        PaymentItemOutcome refused = report.items().get(0);
        assertThat(refused.endToEndId()).isEqualTo("E2E-1");
        assertThat(refused.status()).isEqualTo(PaymentItemOutcome.Status.REJECTED);
        assertThat(refused.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
        assertThat(refused.reason()).isNotBlank();
        assertThat(refused.transferId()).isNull();
        assertThat(report.items().get(1).status()).isEqualTo(PaymentItemOutcome.Status.ACCEPTED);
        assertThat(report.acceptedCount()).isEqualTo(1);
        assertThat(report.rejectedCount()).isEqualTo(1);
    }

    @Test
    void shouldReportConcurrentChangeAsConflict() {
        when(transferService.transfer(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"))
                .thenAnswer(call -> completed(call.getArgument(0), false));

        PaymentBatchReport report = service.process("batch.json", null, emptyFile());

        assertThat(report.items().get(0).errorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(report.items().get(1).status()).isEqualTo(PaymentItemOutcome.Status.ACCEPTED);
    }

    @Test
    void shouldMarkReplayedPayments() {
        when(transferService.transfer(any())).thenAnswer(call -> completed(call.getArgument(0), true));

        PaymentBatchReport report = service.process("batch.json", null, emptyFile());

        assertThat(report.items()).allSatisfy(item -> assertThat(item.replayed()).isTrue());
        assertThat(report.acceptedCount()).isEqualTo(2);
    }

    @Test
    void shouldPayNothingForInvalidFile() {
        PaymentFileReader broken = new PaymentFileReader() {
            @Override
            public PaymentFileFormat format() {
                return PaymentFileFormat.JSON;
            }

            @Override
            public PaymentBatch read(InputStream content) {
                throw new InvalidPaymentFileException(List.of("$.payments: must have at least 1 item"));
            }
        };
        service = new PaymentBatchService(List.of(broken, reader(PaymentFileFormat.XML, BATCH)), transferService);

        assertThat(codeOf(() -> service.process("batch.json", null, emptyFile())))
                .isEqualTo(ErrorCode.INVALID_PAYMENT_FILE);
        verifyNoInteractions(transferService);
    }

    @Test
    void shouldPayNothingForUnsupportedFile() {
        assertThat(codeOf(() -> service.process("batch.csv", "text/csv", emptyFile())))
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_FORMAT);
        verifyNoInteractions(transferService);
    }

    @Test
    void shouldFailWithoutReaderForEveryFormat() {
        assertThatThrownBy(() -> new PaymentBatchService(List.of(reader(PaymentFileFormat.JSON, BATCH)),
                transferService))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("XML");
    }

    @Test
    void shouldFailWithTwoReadersForOneFormat() {
        assertThatThrownBy(() -> new PaymentBatchService(List.of(reader(PaymentFileFormat.JSON, BATCH),
                reader(PaymentFileFormat.JSON, BATCH), reader(PaymentFileFormat.XML, BATCH)), transferService))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JSON");
    }

    private static PaymentFileReader reader(PaymentFileFormat format, PaymentBatch batch) {
        return new PaymentFileReader() {
            @Override
            public PaymentFileFormat format() {
                return format;
            }

            @Override
            public PaymentBatch read(InputStream content) {
                return batch;
            }
        };
    }

    private static TransferResult completed(TransferCommand command, boolean replayed) {
        Transfer transfer = Transfer.completed(command, Instant.parse("2026-11-09T10:00:00Z"));
        ReflectionTestUtils.setField(transfer, "id", UUID.randomUUID());
        return new TransferResult(transfer, replayed);
    }

    private static InputStream emptyFile() {
        return new ByteArrayInputStream(new byte[0]);
    }
}
