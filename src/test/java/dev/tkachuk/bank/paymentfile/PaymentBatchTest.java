package dev.tkachuk.bank.paymentfile;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.bank.support.TestIbans;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaymentBatchTest {

    @Test
    void shouldListRepeatedEndToEndIdsOnceInOrder() {
        PaymentBatch batch = batchOf("E2E-2", "E2E-1", "E2E-2", "E2E-3", "E2E-1", "E2E-2");

        assertThat(batch.repeatedEndToEndIds()).containsExactly("E2E-2", "E2E-1");
    }

    @Test
    void shouldListNoneWhenIdsAreUnique() {
        assertThat(batchOf("E2E-1", "E2E-2", "E2E-3").repeatedEndToEndIds()).isEmpty();
    }

    private static PaymentBatch batchOf(String... endToEndIds) {
        List<PaymentInstruction> payments = Arrays.stream(endToEndIds)
                .map(id -> new PaymentInstruction(id, TestIbans.B, "Olena Kovalenko", new BigDecimal("1.00"), "UAH",
                        null))
                .toList();
        return new PaymentBatch("MSG-1", TestIbans.A, payments);
    }
}
