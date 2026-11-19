package dev.tkachuk.bank.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.springframework.core.io.ClassPathResource;

public final class PaymentFiles {

    private PaymentFiles() {
    }

    public static InputStream open(String name) {
        try {
            return new ClassPathResource("payment-files/" + name).getInputStream();
        } catch (IOException e) {
            throw new UncheckedIOException("Missing test file payment-files/" + name, e);
        }
    }

    public static byte[] bytes(String name) {
        try {
            return new ClassPathResource("payment-files/" + name).getContentAsByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Missing test file payment-files/" + name, e);
        }
    }
}
