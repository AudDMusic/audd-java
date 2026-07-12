package io.audd.internal;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.util.Locale;

/**
 * Shared, leniently-configured {@link ObjectMapper} for all response parsing.
 *
 * <p>A successful API response must never fail to parse because a field is
 * absent or a different type than expected. The enterprise endpoint, for
 * example, legitimately returns matches with no {@code score} (and no
 * {@code isrc}/{@code upc}/{@code label}). The mapper here is configured so
 * that missing, unknown, and null-for-primitive fields all degrade to
 * sensible defaults instead of throwing, and so that a wrong-but-convertible
 * scalar is coerced into the declared type instead of being dropped.</p>
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
     *       decodes;</li>
     *   <li>a wrong-but-convertible scalar (a number for a String field, a
     *       numeric string for an int field, and so on) is coerced into the
     *       declared type; only the genuinely unconvertible degrades to
     *       {@code null}.</li>
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
        // An empty string for a boolean field should reach the whitelist below
        // (where "" maps to false) instead of Jackson's default empty-string
        // -> null. Route it through the coercion/handler path.
        mapper.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Boolean)
            .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.EmptyString,
                         com.fasterxml.jackson.databind.cfg.CoercionAction.TryConvert);
        mapper.registerModule(scalarShapeGuardModule());
        mapper.addHandler(lenientProblemHandler());
        return mapper;
    }

    /**
     * A module that wraps every scalar (number/boolean) value deserializer so a
     * container arriving where a scalar is expected is skipped to {@code null}
     * rather than crashing.
     *
     * <p>Jackson's stock number deserializers try to coerce a
     * {@code START_OBJECT}/{@code START_ARRAY} through a from-string path whose
     * input is {@code null}, which throws before {@link
     * DeserializationProblemHandler#handleUnexpectedToken} ever runs. Guarding
     * here — ahead of the stock logic — keeps the container-for-scalar case on
     * the same degrade-to-{@code null} contract as every other shape drift.</p>
     */
    private static SimpleModule scalarShapeGuardModule() {
        SimpleModule module = new SimpleModule("auddScalarShapeGuard");
        module.setDeserializerModifier(new BeanDeserializerModifier() {
            @Override
            public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config, BeanDescription beanDesc,
                                                          JsonDeserializer<?> deserializer) {
                Class<?> handled = deserializer.handledType();
                if (handled == null) handled = beanDesc.getBeanClass();
                if (isGuardedScalar(handled)) {
                    return new ScalarShapeGuardDeserializer(deserializer);
                }
                return deserializer;
            }
        });
        return module;
    }

    /** Boxed scalar types whose deserializer we wrap. Primitives share these deserializers. */
    private static boolean isGuardedScalar(Class<?> c) {
        return c == Integer.class || c == Long.class || c == Short.class || c == Byte.class
            || c == Double.class || c == Float.class || c == Boolean.class;
    }

    /**
     * Delegates to the stock scalar deserializer, but if the current token is a
     * {@code START_OBJECT}/{@code START_ARRAY} (a container where a scalar is
     * expected), it skips the subtree and yields {@code null}. Every other token
     * — including all the wrong-but-convertible scalars — flows to the delegate
     * and its coercion/handler path unchanged.
     */
    private static final class ScalarShapeGuardDeserializer extends JsonDeserializer<Object> {
        private final JsonDeserializer<?> delegate;

        ScalarShapeGuardDeserializer(JsonDeserializer<?> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonToken t = p.currentToken();
            if (t == JsonToken.START_OBJECT || t == JsonToken.START_ARRAY) {
                p.skipChildren();
                return null;
            }
            return delegate.deserialize(p, ctxt);
        }

        @Override
        public Object getNullValue(DeserializationContext ctxt) throws com.fasterxml.jackson.databind.JsonMappingException {
            return delegate.getNullValue(ctxt);
        }
    }

    /**
     * A last line of defence for shape drift that the feature flags above don't
     * cover. Two jobs:
     *
     * <ol>
     *   <li><b>Coerce convertible wrong-typed scalars.</b> A field that arrives
     *       as a scalar of the wrong-but-convertible JSON type is rendered into
     *       the declared type rather than dropped:
     *       <ul>
     *         <li>expecting a String: a JSON number/boolean becomes its rendered
     *             text ({@code 85 -> "85"}, {@code true -> "true"});</li>
     *         <li>expecting an int/long: a whole-number string parses, a
     *             float/double truncates, a boolean maps to {@code 0}/{@code 1};</li>
     *         <li>expecting a float/double: a numeric string parses and an int
     *             converts;</li>
     *         <li>expecting a boolean: a number maps by {@code value != 0} and a
     *             string maps through a fixed whitelist
     *             ({@code true/1/yes/on -> true}, {@code false/0/no/off/"" -> false}).</li>
     *       </ul>
     *       Numeric-string parsing is strict over the whole trimmed string, so
     *       {@code "85abc"}, {@code "NaN"} and {@code "Infinity"} do not slip
     *       through as numbers.</li>
     *   <li><b>Degrade the genuinely unconvertible to {@code null}.</b> A scalar
     *       field arriving as an object/array, a numeric field carrying a
     *       non-numeric string ({@code "n/a"}), a boolean field carrying an
     *       out-of-whitelist string ({@code "maybe"}), a provider block arriving
     *       as a bare string, or an uncoercible map key are all skipped and the
     *       field left {@code null} — never a garbage {@code 0}/{@code false}.
     *       Only genuinely undecodable JSON, transport errors, {@code
     *       status=error} bodies, and caller-input errors are allowed to
     *       surface.</li>
     * </ol>
     *
     * <p>All of this lives only on the failure path; the fast path where the
     * wire type already matches the model is untouched.</p>
     */
    private static DeserializationProblemHandler lenientProblemHandler() {
        return new DeserializationProblemHandler() {
            /**
             * A scalar-typed field arriving as an unexpected token. Coerce the
             * convertible cross-scalar cases (number/boolean into a String
             * field; boolean into a numeric field); skip objects, arrays, and
             * anything else to {@code null}.
             */
            @Override
            public Object handleUnexpectedToken(DeserializationContext ctxt, JavaType targetType,
                                                JsonToken t, JsonParser p, String failureMsg) throws IOException {
                Class<?> raw = targetType.getRawClass();
                if (raw == String.class) {
                    // number / boolean -> rendered string; object / array -> null.
                    switch (t) {
                        case VALUE_NUMBER_INT:
                        case VALUE_NUMBER_FLOAT:
                        case VALUE_TRUE:
                        case VALUE_FALSE:
                            return p.getText();
                        default:
                            break;
                    }
                } else if (t == JsonToken.VALUE_TRUE || t == JsonToken.VALUE_FALSE) {
                    long b = (t == JsonToken.VALUE_TRUE) ? 1L : 0L;
                    if (isIntegral(raw)) return toIntegral(b, raw);
                    if (isFloating(raw)) return toFloating(b, raw);
                }
                skipCurrentValue(p, t);
                return null;
            }

            /**
             * A string arriving for a non-string target. Parse whole-string
             * numerics for numeric targets, map the boolean whitelist for
             * boolean targets; otherwise degrade to {@code null}.
             */
            @Override
            public Object handleWeirdStringValue(DeserializationContext ctxt, Class<?> targetType,
                                                 String valueToConvert, String failureMsg) {
                String s = valueToConvert == null ? "" : valueToConvert.trim();
                if (targetType == Boolean.class || targetType == boolean.class) {
                    return stringToBoolean(s);
                }
                if (isIntegral(targetType)) {
                    Long l = parseLongStrict(s);
                    if (l == null) {
                        Double d = parseDoubleStrict(s);   // "8.9" for an int field -> truncate.
                        if (d == null) return null;
                        l = (long) (double) d;
                    }
                    return toIntegral(l, targetType);
                }
                if (isFloating(targetType)) {
                    Double d = parseDoubleStrict(s);
                    return d == null ? null : toFloating(d, targetType);
                }
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

    private static boolean isIntegral(Class<?> c) {
        return c == Integer.class || c == int.class
            || c == Long.class || c == long.class
            || c == Short.class || c == short.class
            || c == Byte.class || c == byte.class;
    }

    private static boolean isFloating(Class<?> c) {
        return c == Double.class || c == double.class
            || c == Float.class || c == float.class;
    }

    /** Box a long into the concrete integral type the field declares. */
    private static Object toIntegral(long v, Class<?> c) {
        if (c == Integer.class || c == int.class) return (int) v;
        if (c == Short.class || c == short.class) return (short) v;
        if (c == Byte.class || c == byte.class) return (byte) v;
        return v; // Long / long
    }

    /** Box a double into the concrete floating type the field declares. */
    private static Object toFloating(double v, Class<?> c) {
        if (c == Float.class || c == float.class) return (float) v;
        return v; // Double / double
    }

    /**
     * Map a trimmed string onto a boolean via a fixed whitelist.
     * {@code true/1/yes/on -> true}; {@code false/0/no/off/"" -> false};
     * anything else -> {@code null} (never a defaulted {@code false}).
     */
    private static Boolean stringToBoolean(String trimmed) {
        String s = trimmed.toLowerCase(Locale.ROOT);
        switch (s) {
            case "true":
            case "1":
            case "yes":
            case "on":
                return Boolean.TRUE;
            case "false":
            case "0":
            case "no":
            case "off":
            case "":
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    /** Parse an entire trimmed string as a base-10 long, or {@code null}. */
    private static Long parseLongStrict(String trimmed) {
        if (trimmed.isEmpty()) return null;
        try {
            return Long.valueOf(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parse an entire trimmed string as a finite double, or {@code null}.
     * Rejects the non-numeric forms {@link Double#parseDouble} otherwise
     * accepts ({@code NaN}, {@code Infinity}, hex floats, trailing {@code d}/
     * {@code f} suffixes).
     */
    private static Double parseDoubleStrict(String trimmed) {
        if (trimmed.isEmpty()) return null;
        // Reject anything Double.parseDouble accepts but this policy does not:
        // NaN/Infinity, hex floats (0x...p...), and d/f/D/F type suffixes.
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            boolean ok = (ch >= '0' && ch <= '9') || ch == '.' || ch == '-' || ch == '+' || ch == 'e' || ch == 'E';
            if (!ok) return null;
        }
        try {
            double d = Double.parseDouble(trimmed);
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
