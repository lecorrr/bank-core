package dev.tkachuk.bank.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TimeConfigTest {

    private final Clock clock = new TimeConfig().clock();

    @Test
    void tellsTheTimeInUtc() {
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    /** PostgreSQL stores microseconds: anything finer would not read back as it was written. */
    @Test
    void ticksInWholeMicroseconds() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(clock.instant().getNano() % 1_000).isZero();
        }
    }
}
