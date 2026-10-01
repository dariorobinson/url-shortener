package com.schwab.urlshortener.controller.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * Body of {@code PATCH /api/v1/urls/{code}} (D34, D114, D122). A class rather than a record because a record cannot
 * tell a missing field from JSON {@code null}, and for {@code expiresAt} that is the difference between "unchanged"
 * and "clear". Each setter records that its field was present; Jackson calls it for an explicit {@code null} too.
 *
 * <p>{@code active} keeps D89 (only a real JSON boolean) and {@code expiresAt} is parsed strictly (D123). Unknown
 * fields and duplicate keys still fail (D59). The body holds no URL, but it is never logged.
 */
@Schema(description = "At least one of active and expiresAt must be present.")
public class UpdateShortUrlRequest {

    private Boolean active;
    private boolean activePresent;
    private OffsetDateTime expiresAt;
    private boolean expiresAtPresent;

    @Schema(description = "false deactivates the short URL, true reactivates it. Only a JSON boolean is accepted; "
            + "null is rejected.", nullable = false, example = "false")
    public Boolean getActive() {
        return active;
    }

    @JsonSetter("active")
    public void setActive(Boolean active) {
        this.active = active;
        this.activePresent = true;
    }

    @Schema(description = "Sets, extends or shortens the expiry (ISO-8601 with an explicit offset or Z, strictly in "
            + "the future, at most 10 years ahead); null clears it so the link never expires; omit it to leave the "
            + "expiry unchanged.", type = "string", format = "date-time", nullable = true,
            example = "2027-01-31T23:59:59Z")
    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    @JsonSetter("expiresAt")
    @JsonDeserialize(using = StrictOffsetDateTimeDeserializer.class)
    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
        this.expiresAtPresent = true;
    }

    /** True if the body contained {@code active}, even as JSON null. */
    @JsonIgnore
    public boolean hasActive() {
        return activePresent;
    }

    /** True if the body contained {@code expiresAt}, even as JSON null (which means "clear"). */
    @JsonIgnore
    public boolean hasExpiresAt() {
        return expiresAtPresent;
    }
}
