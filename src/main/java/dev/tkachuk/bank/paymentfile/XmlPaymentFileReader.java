package dev.tkachuk.bank.paymentfile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.springframework.stereotype.Component;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;

/**
 * Reads simplified ISO 20022 pain.001 XML files, validated against /schemas/pain001-simplified.xsd.
 *
 * <p>The file is validated first, by a parser that refuses any DOCTYPE, so no entity can ever be expanded:
 * that rules out XXE (an entity that reads a local file or calls another server) and "billion laughs" (entities
 * that expand to gigabytes). Only a valid file is then read, with StAX, and checked for what XSD 1.0 cannot
 * express: the totals in the group header and unique end-to-end ids.
 */
@Component
public class XmlPaymentFileReader implements PaymentFileReader {

    static final String SCHEMA_RESOURCE = "/schemas/pain001-simplified.xsd";

    private static final String DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";

    private final Schema schema = loadSchema();

    public XmlPaymentFileReader() {
        // Fails at startup, not on the first upload, if this JDK's parser cannot be made safe.
        newSafeXmlReader();
    }

    @Override
    public PaymentFileFormat format() {
        return PaymentFileFormat.XML;
    }

    @Override
    public PaymentBatch read(InputStream content) {
        // Uploads are limited to 5 MB, so the file is read once and then validated and parsed from memory.
        byte[] file = readAll(content);
        List<String> violations = validate(file);
        if (!violations.isEmpty()) {
            throw new InvalidPaymentFileException(violations);
        }
        ParsedFile parsed = parse(file);
        PaymentBatch batch = parsed.batch();
        List<String> ruleViolations = checkRules(parsed, batch);
        if (!ruleViolations.isEmpty()) {
            throw new InvalidPaymentFileException(ruleViolations);
        }
        return batch;
    }

    /** XSD validation, collecting every error with its line and column. */
    private List<String> validate(byte[] file) {
        List<String> violations = new ArrayList<>();
        Validator validator = schema.newValidator();
        validator.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException e) {
                // Warnings are not violations.
            }

            @Override
            public void error(SAXParseException e) {
                violations.add(describe(e));
            }

            @Override
            public void fatalError(SAXParseException e) throws SAXParseException {
                // The parser cannot go on after a fatal error (not XML at all, or a DOCTYPE): stop here.
                throw e;
            }
        });
        try {
            validator.validate(new SAXSource(newSafeXmlReader(), new InputSource(new ByteArrayInputStream(file))));
        } catch (SAXParseException e) {
            violations.add(describe(e));
        } catch (SAXException e) {
            violations.add("The file is not readable XML: " + e.getMessage());
        } catch (IOException e) {
            // Reading from memory, so this is about the bytes themselves, such as an invalid encoding.
            violations.add("The file cannot be read as XML: " + e.getMessage());
        }
        return violations;
    }

    /** Runs only on a file the schema accepted, so every element it needs is there and well formed. */
    private static ParsedFile parse(byte[] file) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        ParsedFile parsed = new ParsedFile();
        // The names of the open elements, innermost first: an IBAN is the debtor's under DbtrAcct and the
        // payment's under CdtrAcct.
        Deque<String> open = new ArrayDeque<>();
        PaymentFields payment = new PaymentFields();
        try {
            XMLStreamReader xml = factory.createXMLStreamReader(new ByteArrayInputStream(file));
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String parent = open.peek();
                    String element = xml.getLocalName();
                    open.push(element);
                    readElement(xml, element, parent, parsed, payment);
                    // Reading an element's text moves the reader to its end tag, which is then not seen below.
                    if (xml.getEventType() == XMLStreamConstants.END_ELEMENT) {
                        open.pop();
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if ("CdtTrfTxInf".equals(xml.getLocalName())) {
                        parsed.payments.add(payment.toInstruction());
                        payment = new PaymentFields();
                    }
                    open.pop();
                }
            }
        } catch (XMLStreamException e) {
            throw new InvalidPaymentFileException(List.of("The file is not readable XML: " + e.getMessage()));
        }
        return parsed;
    }

    private static void readElement(XMLStreamReader xml, String element, String parent, ParsedFile parsed,
            PaymentFields payment) throws XMLStreamException {
        switch (element) {
            case "MsgId" -> parsed.messageId = text(xml);
            case "NbOfTxs" -> parsed.declaredCount = new BigInteger(text(xml));
            case "CtrlSum" -> parsed.declaredSum = new BigDecimal(text(xml));
            case "IBAN" -> {
                if ("DbtrAcct".equals(parent)) {
                    parsed.debtorIban = text(xml);
                } else if ("CdtrAcct".equals(parent)) {
                    payment.creditorIban = text(xml);
                }
            }
            case "EndToEndId" -> payment.endToEndId = text(xml);
            case "InstdAmt" -> {
                payment.currency = xml.getAttributeValue(null, "Ccy");
                payment.amount = new BigDecimal(text(xml));
            }
            case "Nm" -> {
                if ("Cdtr".equals(parent)) {
                    payment.creditorName = text(xml);
                }
            }
            case "Ustrd" -> payment.remittanceInfo = text(xml);
            default -> {
                // A container element: its children are read when the reader gets to them.
            }
        }
    }

    private static String text(XMLStreamReader xml) throws XMLStreamException {
        return xml.getElementText().strip();
    }

    /** What the schema cannot express: the totals in the header and unique end-to-end ids. */
    private static List<String> checkRules(ParsedFile parsed, PaymentBatch batch) {
        List<String> violations = new ArrayList<>();
        int count = batch.payments().size();
        if (!parsed.declaredCount.equals(BigInteger.valueOf(count))) {
            violations.add("GrpHdr/NbOfTxs is " + parsed.declaredCount + " but the file has " + count
                    + " CdtTrfTxInf");
        }
        BigDecimal sum = batch.payments().stream()
                .map(PaymentInstruction::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (parsed.declaredSum.compareTo(sum) != 0) {
            violations.add("GrpHdr/CtrlSum is " + parsed.declaredSum.toPlainString() + " but the amounts add up to "
                    + sum.toPlainString());
        }
        for (String id : batch.repeatedEndToEndIds()) {
            violations.add("EndToEndId " + id + " is used by more than one CdtTrfTxInf");
        }
        return violations;
    }

    /** A namespace-aware SAX parser that refuses any DOCTYPE, for the validator to read the file with. */
    private static XMLReader newSafeXmlReader() {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature(DISALLOW_DOCTYPE, true);
            return factory.newSAXParser().getXMLReader();
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalStateException("This JDK's SAX parser cannot refuse DOCTYPEs: " + e.getMessage(), e);
        }
    }

    private static byte[] readAll(InputStream content) {
        try {
            return content.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded file", e);
        }
    }

    private static String describe(SAXParseException e) {
        return "line " + e.getLineNumber() + ", column " + e.getColumnNumber() + ": " + e.getMessage();
    }

    private static Schema loadSchema() {
        try (InputStream xsd = XmlPaymentFileReader.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (xsd == null) {
                throw new IllegalStateException("Missing " + SCHEMA_RESOURCE + " on the classpath");
            }
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // The schema is self-contained: it may not pull in anything from outside.
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newSchema(new StreamSource(xsd));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + SCHEMA_RESOURCE, e);
        } catch (SAXException e) {
            throw new IllegalStateException("Invalid schema " + SCHEMA_RESOURCE + ": " + e.getMessage(), e);
        }
    }

    /** The file as read, including the header totals that PaymentBatch does not carry. */
    private static final class ParsedFile {
        private String messageId;
        private String debtorIban;
        private BigInteger declaredCount;
        private BigDecimal declaredSum;
        private final List<PaymentInstruction> payments = new ArrayList<>();

        PaymentBatch batch() {
            return new PaymentBatch(messageId, debtorIban, payments);
        }
    }

    private static final class PaymentFields {
        private String endToEndId;
        private String creditorIban;
        private String creditorName;
        private BigDecimal amount;
        private String currency;
        private String remittanceInfo;

        PaymentInstruction toInstruction() {
            return new PaymentInstruction(endToEndId, creditorIban, creditorName, amount, currency, remittanceInfo);
        }
    }
}
