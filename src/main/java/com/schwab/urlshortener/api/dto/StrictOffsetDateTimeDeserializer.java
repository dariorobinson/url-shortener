package com.schwab.urlshortener.api.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * D123: {@code expiresAt} is accepted only as a JSON string in ISO-8601 with an explicit offset or {@code Z}. The
 * JSR-310 deserializers would also accept epoch numbers, and an offset-less local date-time would be read in some
 * implicit zone; both are rejected here, so the global Jackson settings and other date fields are unaffected.
 * A rejection surfaces as {@code 400 MALFORMED_REQUEST}, like every unreadable body (D59).
 */
public class StrictOffsetDateTimeDeserializer extends StdScalarDeserializer<OffsetDateTime> {

    public StrictOffsetDateTimeDeserializer() {
        super(OffsetDateTime.class);
    }

    @Override
    public OffsetDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return (OffsetDateTime) context.handleUnexpectedToken(OffsetDateTime.class, parser);
        }
        try {
            return OffsetDateTime.parse(parser.getText(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException e) {
            return (OffsetDateTime) context.handleWeirdStringValue(OffsetDateTime.class, parser.getText(),
                    "expected an ISO-8601 date-time with an offset");
        }
    }
}
