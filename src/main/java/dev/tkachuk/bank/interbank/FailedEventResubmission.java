package dev.tkachuk.bank.interbank;

import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Resends outbox events that failed to reach Kafka (FAILED in event_publication), without a restart. */
@Component
class FailedEventResubmission {

    private final FailedEventPublications failed;

    FailedEventResubmission(FailedEventPublications failed) {
        this.failed = failed;
    }

    @Scheduled(fixedDelayString = "${bank.interbank.resubmit-failed-events-every:PT30S}")
    void resubmitFailedEvents() {
        failed.resubmit(ResubmissionOptions.defaults());
    }
}
