package dev.tkachuk.bank.interbank;

import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The outbox's retry. An event Kafka did not take (it was down, say) is marked FAILED and stays in the
 * event_publication table; so is one that a crash left half sent, once the staleness check in application.yml
 * finds it. This sends the FAILED ones again, so a transfer's event reaches bank-integration once Kafka is back,
 * without a restart. Only FAILED ones: an event still on its way is left alone, so this job never sends one twice
 * at the same moment.
 */
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
