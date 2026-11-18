package dev.tkachuk.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.bank.support.IntegrationTest;
import dev.tkachuk.bank.support.TestIbans;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

/**
 * Many transfers at the same moment against a real PostgreSQL. These catch what mocks cannot:
 * lost updates, deadlocks and double spending.
 */
class TransferConcurrencyIT extends IntegrationTest {

    private static final int THREADS = 8;
    private static final long TIMEOUT_SECONDS = 60;

    @Autowired
    private TransferService transferService;

    /** 50 transfers each way at once. Locking the two rows in a different order per direction deadlocks here. */
    @Test
    void transfersInOppositeDirectionsAllSucceedAndLoseNothing() throws Exception {
        insertAccount(TestIbans.A, "Artem Demo", "UAH", "10000.00");
        insertAccount(TestIbans.B, "Olena Kovalenko", "UAH", "10000.00");
        List<Callable<TransferResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(transferTask("a-to-b-" + i, TestIbans.A, TestIbans.B));
            tasks.add(transferTask("b-to-a-" + i, TestIbans.B, TestIbans.A));
        }

        List<Outcome> outcomes = runAllAtOnce(tasks);

        assertThat(outcomes).as("every transfer succeeds; a failure here is usually a deadlock")
                .allSatisfy(outcome -> assertThat(outcome.error()).isNull());
        assertThat(balanceOf(TestIbans.A)).isEqualByComparingTo("10000.00");
        assertThat(balanceOf(TestIbans.B)).isEqualByComparingTo("10000.00");
        assertThat(countRows("transfers")).isEqualTo(100);
        assertThat(countRows("ledger_entries")).isEqualTo(200);
    }

    /** A client retrying a request it thinks timed out: ten copies of one request arrive together. */
    @Test
    void theSameRequestSentTenTimesAtOnceMovesMoneyOnce() throws Exception {
        insertAccount(TestIbans.A, "Artem Demo", "UAH", "1000.00");
        insertAccount(TestIbans.B, "Olena Kovalenko", "UAH", "0.00");
        List<Callable<TransferResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(transferTask("same-key", TestIbans.A, TestIbans.B, "100.00"));
        }

        List<Outcome> outcomes = runAllAtOnce(tasks);

        long created = outcomes.stream().filter(o -> o.result() != null && !o.result().replayed()).count();
        assertThat(created).as("requests that moved money").isEqualTo(1);
        // The others are replays, or the database refused the duplicate: both are safe for the client to retry.
        assertThat(outcomes).allSatisfy(outcome -> {
            if (outcome.error() != null) {
                assertThat(outcome.error()).isInstanceOf(DataAccessException.class);
            }
        });
        assertThat(balanceOf(TestIbans.A)).isEqualByComparingTo("900.00");
        assertThat(balanceOf(TestIbans.B)).isEqualByComparingTo("100.00");
        assertThat(countRows("transfers")).isEqualTo(1);
        assertThat(countRows("ledger_entries")).isEqualTo(2);
    }

    private Callable<TransferResult> transferTask(String key, String fromIban, String toIban) {
        return transferTask(key, fromIban, toIban, "10.00");
    }

    private Callable<TransferResult> transferTask(String key, String fromIban, String toIban, String amount) {
        TransferCommand command = new TransferCommand(key, fromIban, toIban, new BigDecimal(amount), "UAH",
                "Concurrency test");
        return () -> transferService.transfer(command);
    }

    /** Starts every task at the same moment, waits for all of them and records how each one ended. */
    private static List<Outcome> runAllAtOnce(List<Callable<TransferResult>> tasks) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<TransferResult>> futures = new ArrayList<>();
            for (Callable<TransferResult> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<TransferResult> future : futures) {
                outcomes.add(outcomeOf(future));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static Outcome outcomeOf(Future<TransferResult> future) throws InterruptedException {
        try {
            return new Outcome(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), null);
        } catch (ExecutionException e) {
            return new Outcome(null, e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("A transfer did not finish within " + TIMEOUT_SECONDS + " s: stuck on a lock?", e);
        }
    }

    /** Exactly one of the two is set. */
    private record Outcome(TransferResult result, Throwable error) {
    }
}
