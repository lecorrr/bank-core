package dev.tkachuk.bank.paymentfile;

import java.io.InputStream;

/** One payment file format; a new format is a new implementation. */
public interface PaymentFileReader {

    PaymentFileFormat format();

    PaymentBatch read(InputStream content);
}
