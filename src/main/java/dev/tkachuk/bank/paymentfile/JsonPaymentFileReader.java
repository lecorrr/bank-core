package dev.tkachuk.bank.paymentfile;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Reads payment-batch JSON files, validated against /schemas/payment-batch.schema.json (JSON Schema 2020-12).
 * The schema validator works on Jackson 2 trees, so this class reads with Jackson 2 while the rest of the
 * application uses Spring Boot's Jackson 3; the two live in different packages and never meet.
 */
@Component
public class JsonPaymentFileReader implements PaymentFileReader {

    static final String SCHEMA_RESOURCE = "/schemas/payment-batch.schema.json";

    /**
     * Amounts become BigDecimal straight from the text, written as in the file: through a double, 200.50 would
     * already be 200.499999... A key given twice is an error instead of "the last one wins", because a payment
     * file must not be ambiguous about an amount or an account; so is anything after the JSON document.
     */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final JsonSchema schema = loadSchema();

    @Override
    public PaymentFileFormat format() {
        return PaymentFileFormat.JSON;
    }

    @Override
    public PaymentBatch read(InputStream content) {
        JsonNode file = parse(content);
        List<String> violations = schema.validate(file).stream()
                .map(ValidationMessage::getMessage)
                .sorted()
                .toList();
        if (!violations.isEmpty()) {
            throw new InvalidPaymentFileException(violations);
        }
        PaymentBatch batch = toBatch(file);
        List<String> repeated = batch.repeatedEndToEndIds();
        if (!repeated.isEmpty()) {
            throw new InvalidPaymentFileException(repeated.stream()
                    .map(id -> "$.payments: endToEndId " + id + " is used by more than one payment")
                    .toList());
        }
        return batch;
    }

    private static JsonNode parse(InputStream content) {
        JsonNode file;
        try {
            file = JSON.readTree(content);
        } catch (JsonProcessingException e) {
            throw new InvalidPaymentFileException(
                    List.of("The file is not valid JSON" + where(e.getLocation()) + ": " + e.getOriginalMessage()));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded file", e);
        }
        // An empty file is not an error to Jackson: it reads as a missing node.
        if (file == null || file.isMissingNode()) {
            throw new InvalidPaymentFileException(List.of("The file is empty"));
        }
        return file;
    }

    private static String where(JsonLocation location) {
        return location == null ? "" : " (line " + location.getLineNr() + ", column " + location.getColumnNr() + ")";
    }

    /** Runs only on a file the schema accepted, so every required field is there with the right type. */
    private static PaymentBatch toBatch(JsonNode file) {
        List<PaymentInstruction> payments = new ArrayList<>();
        for (JsonNode payment : file.get("payments")) {
            payments.add(new PaymentInstruction(
                    payment.get("endToEndId").textValue(),
                    payment.get("creditorIban").textValue(),
                    payment.get("creditorName").textValue(),
                    payment.get("amount").decimalValue(),
                    payment.get("currency").textValue(),
                    // Optional: a missing node has no text, so this is null when the file has none.
                    payment.path("remittanceInfo").textValue()));
        }
        return new PaymentBatch(file.get("messageId").textValue(), file.get("debtorIban").textValue(), payments);
    }

    private static JsonSchema loadSchema() {
        try (InputStream schemaJson = JsonPaymentFileReader.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (schemaJson == null) {
                throw new IllegalStateException("Missing " + SCHEMA_RESOURCE + " on the classpath");
            }
            // English whatever the server's locale: the messages go back to the caller as the API's errors.
            SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().locale(Locale.ENGLISH).build();
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaJson, config);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + SCHEMA_RESOURCE, e);
        }
    }
}
