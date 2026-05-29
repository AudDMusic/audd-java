package io.audd.internal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared, leniently-configured {@link ObjectMapper} for all response parsing.
 *
 * <p>A successful API response must never fail to parse because a field is
 * absent or a different type than expected. The enterprise endpoint, for
 * example, legitimately returns matches with no {@code score} (and no
 * {@code isrc}/{@code upc}/{@code label}). The mapper here is configured so
 * that missing, unknown, and null-for-primitive fields all degrade to
 * sensible defaults instead of throwing.</p>
 *
 * <p>This does not weaken the error contract: a {@code status=error} body is
 * still turned into a typed exception before any model is decoded, and a body
 * that is not valid JSON at all still surfaces as a serialization error.</p>
 */
public final class Json {
    private static final ObjectMapper MAPPER = newLenientMapper();

    private Json() {}

    /** The shared lenient mapper. Jackson {@link ObjectMapper} is thread-safe once configured. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * Build a mapper that tolerates the natural shape variation of AudD
     * responses:
     * <ul>
     *   <li>unknown keys are ignored (typed models also absorb them via
     *       {@code @JsonAnySetter}, but this protects any plain POJO too);</li>
     *   <li>a JSON {@code null} for a primitive field becomes the primitive's
     *       default rather than throwing;</li>
     *   <li>a missing {@code @JsonCreator} property is allowed;</li>
     *   <li>scalars accepted for single-element arrays and vice versa, so a
     *       field that is sometimes an object and sometimes a list still
     *       decodes.</li>
     * </ul>
     */
    public static ObjectMapper newLenientMapper() {
        return new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false)
            .configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, false)
            .configure(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS, false)
            .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true)
            .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
            .configure(DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS, true)
            .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
            .configure(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT, true);
    }
}
