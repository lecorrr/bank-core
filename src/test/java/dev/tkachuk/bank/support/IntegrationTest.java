package dev.tkachuk.bank.support;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.jdbc.JdbcTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/** Spring context with PostgreSQL and Kafka in Testcontainers; every test starts from an empty database. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, KafkaTestcontainer.class})
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE ledger_entries, transfers, event_publication RESTART IDENTITY");
        jdbc.execute("DELETE FROM accounts WHERE kind = 'CUSTOMER'");
        // The internal accounts come from a migration, so they stay; only their balances are reset.
        jdbc.execute("UPDATE accounts SET balance = 0");
    }

    protected long insertAccount(String iban, String holderName, String currency, String balance) {
        Long id = jdbc.queryForObject("""
                INSERT INTO accounts (iban, holder_name, currency, balance, version, created_at)
                VALUES (?, ?, ?, ?, 0, now())
                RETURNING id
                """, Long.class, iban, holderName, currency, new BigDecimal(balance));
        if (id == null) {
            throw new IllegalStateException("Inserting account " + iban + " returned no id");
        }
        return id;
    }

    protected BigDecimal balanceOf(String iban) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE iban = ?", BigDecimal.class, iban);
    }

    protected int countRows(String table) {
        return JdbcTestUtils.countRowsInTable(jdbc, table);
    }

    protected int countCustomerAccounts() {
        return JdbcTestUtils.countRowsInTableWhere(jdbc, "accounts", "kind = 'CUSTOMER'");
    }
}
