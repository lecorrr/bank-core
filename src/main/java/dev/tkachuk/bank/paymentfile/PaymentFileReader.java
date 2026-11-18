package dev.tkachuk.bank.paymentfile;

import java.io.InputStream;

/** Reads one payment file format. Adding a format means adding one more implementation. */
public interface PaymentFileReader {

    PaymentFileFormat format();

    /**
     * Validates the whole file and returns its payments.
     *
     * @throws InvalidPaymentFileException listing every problem found, when the file is not valid
     */
    PaymentBatch read(InputStream content);
}
