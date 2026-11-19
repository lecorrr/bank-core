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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Reads payment-batch JSON. Uses Jackson 2, which the JSON Schema validator needs. */
@Component
public class JsonPaymentFileReader implements PaymentFileReader {

    static final String SCHEMA_RESOURCE = "/schemas/payment-batch.schema.json";

    // BigDecimal straight from the text; duplicate keys and trailing content are errors.
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
        List<String> ruleViolations = new ArrayList<>(amountsWithMoreThanTwoDecimals(batch));
        for (String id : batch.repeatedEndToEndIds()) {
            ruleViolations.add("$.payments: endToEndId " + id + " is used by more than one payment");
        }
        if (!ruleViolations.isEmpty()) {
            throw new InvalidPaymentFileException(ruleViolations);
        }
        return batch;
    }

    // JSON Schema can't limit decimals by value, so it's checked here.
    private static List<String> amountsWithMoreThanTwoDecimals(PaymentBatch batch) {
        List<String> violations = new ArrayList<>();
        List<PaymentInstruction> payments = batch.payments();
        for (int i = 0; i < payments.size(); i++) {
            BigDecimal amount = payments.get(i).amount();
            if (amount.stripTrailingZeros().scale() > 2) {
                violations.add("$.payments[" + i + "].amount: " + amount.toPlainString()
                        + " has more than two decimals");
            }
        }
        return violations;
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
        if (file == null || file.isMissingNode()) {
            throw new InvalidPaymentFileException(List.of("The file is empty"));
        }
        return file;
    }

    private static String where(JsonLocation location) {
        return location == null ? "" : " (line " + location.getLineNr() + ", column " + location.getColumnNr() + ")";
    }

    private static PaymentBatch toBatch(JsonNode file) {
        List<PaymentInstruction> payments = new ArrayList<>();
        for (JsonNode payment : file.get("payments")) {
            payments.add(new PaymentInstruction(
                    payment.get("endToEndId").textValue(),
                    payment.get("creditorIban").textValue(),
                    payment.get("creditorName").textValue(),
                    payment.get("amount").decimalValue(),
                    payment.get("currency").textValue(),
                    payment.path("remittanceInfo").textValue()));
        }
        return new PaymentBatch(file.get("messageId").textValue(), file.get("debtorIban").textValue(), payments);
    }

    private static JsonSchema loadSchema() {
        try (InputStream schemaJson = JsonPaymentFileReader.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (schemaJson == null) {
                throw new IllegalStateException("Missing " + SCHEMA_RESOURCE + " on the classpath");
            }
            SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().locale(Locale.ENGLISH).build();
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaJson, config);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + SCHEMA_RESOURCE, e);
        }
    }
}
