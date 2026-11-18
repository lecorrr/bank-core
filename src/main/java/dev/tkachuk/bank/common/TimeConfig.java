package dev.tkachuk.bank.common;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Business code asks this clock for the time instead of calling Instant.now(), so tests can fix it. */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    /**
     * UTC, in whole microseconds. PostgreSQL keeps timestamps to the microsecond, so a finer clock would answer a
     * POST with nanoseconds that a later GET of the same row no longer has.
     */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
