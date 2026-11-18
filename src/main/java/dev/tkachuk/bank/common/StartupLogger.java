package dev.tkachuk.bank.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Prints the running version, so a log line always says which build is answering. */
@Slf4j
@Component
class StartupLogger {

    private final ObjectProvider<BuildProperties> buildProperties;

    StartupLogger(ObjectProvider<BuildProperties> buildProperties) {
        this.buildProperties = buildProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void logVersion() {
        BuildProperties build = buildProperties.getIfAvailable();
        if (build == null) {
            log.warn("bank-core started, version unknown: build-info.properties is missing (run mvn package)");
        } else {
            log.info("bank-core {} started", build.getVersion());
        }
    }
}
