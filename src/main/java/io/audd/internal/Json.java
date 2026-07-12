package io.audd.internal;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;

import java.io.IOException;

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
        ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false)
            .configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, false)
            .configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
            .configure(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS, false)
            .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true)
            .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
            .configure(DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS, true)
            .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
            .configure(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT, true);
        mapper.addHandler(lenientProblemHandler());
        return mapper;
    }

    /**
     * A last line of defence for shape drift that the feature flags above don't
     * cover: a field typed as a scalar arriving as an object/array (e.g.
     * {@code spotify.name} as an object), a numeric field arriving as a
     * non-numeric string ({@code score}/{@code audio_id} as {@code "n/a"}), a
     * provider block arriving as a bare string, or an object key that can't be
     * coerced. In every such case we skip the offending value and degrade the
     * field to {@code null} instead of letting the whole response fail to
     * decode. Only genuinely undecodable JSON, transport errors, {@code
     * status=error} bodies, and caller-input errors are allowed to surface.
     */
    private static DeserializationProblemHandler lenientProblemHandler() {
        return new DeserializationProblemHandler() {
            /** Scalar-typed field arriving as an object/array (or any wrong token). */
            @Override
            public Object handleUnexpectedToken(DeserializationContext ctxt, JavaType targetType,
                                                JsonToken t, JsonParser p, String failureMsg) throws IOException {
                skipCurrentValue(p, t);
                return null;
            }

            /** Numeric field arriving as a non-numeric string ({@code "n/a"}). */
            @Override
            public Object handleWeirdStringValue(DeserializationContext ctxt, Class<?> targetType,
                                                 String valueToConvert, String failureMsg) {
                return null;
            }

            /** String field arriving as a number that can't be coerced, etc. */
            @Override
            public Object handleWeirdNativeValue(DeserializationContext ctxt, JavaType targetType,
                                                 Object valueToConvert, JsonParser p) {
                return null;
            }

            /** A number that overflows / can't map to the target numeric type. */
            @Override
            public Object handleWeirdNumberValue(DeserializationContext ctxt, Class<?> targetType,
                                                 Number valueToConvert, String failureMsg) {
                return null;
            }

            /** A map key that can't be coerced to the key type — drop that entry. */
            @Override
            public Object handleWeirdKey(DeserializationContext ctxt, Class<?> rawKeyType,
                                         String keyValue, String failureMsg) {
                return null;
            }

            /** Concrete type has no usable constructor for the incoming shape. */
            @Override
            public Object handleMissingInstantiator(DeserializationContext ctxt, Class<?> instClass,
                                                    com.fasterxml.jackson.databind.deser.ValueInstantiator valueInsta,
                                                    JsonParser p, String msg) throws IOException {
                skipCurrentValue(p, p.currentToken());
                return null;
            }

            /** Construction of a value threw — degrade rather than propagate. */
            @Override
            public Object handleInstantiationProblem(DeserializationContext ctxt, Class<?> instClass,
                                                     Object argument, Throwable t) {
                return null;
            }
        };
    }

    /**
     * Advance the parser past the value currently under the cursor so decoding
     * of sibling fields can continue. For a START_OBJECT/START_ARRAY this skips
     * the whole subtree; for a scalar the cursor already sits on the value and
     * needs no further advance.
     */
    private static void skipCurrentValue(JsonParser p, JsonToken t) throws IOException {
        if (t == JsonToken.START_OBJECT || t == JsonToken.START_ARRAY) {
            p.skipChildren();
        }
    }
}
