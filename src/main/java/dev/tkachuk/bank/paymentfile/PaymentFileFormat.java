package dev.tkachuk.bank.paymentfile;

import dev.tkachuk.bank.common.error.BankException;
import dev.tkachuk.bank.common.error.ErrorCode;
import java.util.Locale;

public enum PaymentFileFormat {
    JSON,
    XML;

    public static PaymentFileFormat detect(String filename, String contentType) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".json")) {
            return JSON;
        }
        if (name.endsWith(".xml")) {
            return XML;
        }
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.contains("json")) {
            return JSON;
        }
        if (type.contains("xml")) {
            return XML;
        }
        throw new BankException(ErrorCode.UNSUPPORTED_FILE_FORMAT,
                "Upload a .json or .xml payment file, got '" + filename + "' (" + contentType + ")");
    }
}
