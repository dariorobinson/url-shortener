package com.schwab.urlshortener.util.shortcode;

/** Produces candidate short codes. Pure: no database access, no knowledge of reserved words. */
public interface ShortCodeGenerator {

    /** Returns a new random code; uniqueness is guaranteed only by the database constraint. */
    String generate();
}
