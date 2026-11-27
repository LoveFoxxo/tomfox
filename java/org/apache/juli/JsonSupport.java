/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.juli;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * Shared JSON implementation. This class is packaged with JULI so it is available both during bootstrap and to the
 * common class loader. Object mappers are immutable after configuration and safe to share; parsers and generators are
 * created per operation.
 */
public final class JsonSupport {

    private static final int MAX_NESTING_DEPTH = 1000;
    private static final int MAX_NUMBER_LENGTH = 16_000_000;
    private static final int MAX_STRING_LENGTH = 20_000_000;
    private static final long MAX_DOCUMENT_LENGTH = 20_000_000L;

    private static final ObjectMapper MAPPER = createMapper();

    private JsonSupport() {
    }

    private static ObjectMapper createMapper() {
        StreamReadConstraints constraints = StreamReadConstraints.builder().maxNestingDepth(MAX_NESTING_DEPTH)
                .maxNumberLength(MAX_NUMBER_LENGTH).maxStringLength(MAX_STRING_LENGTH)
                .maxDocumentLength(MAX_DOCUMENT_LENGTH).build();
        JsonFactory factory = JsonFactory.builder().streamReadConstraints(constraints)
                .disable(StreamReadFeature.AUTO_CLOSE_SOURCE).enable(JsonReadFeature.ALLOW_JAVA_COMMENTS).enable(JsonReadFeature.ALLOW_YAML_COMMENTS)
                .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES).enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
                .enable(JsonReadFeature.ALLOW_LEADING_ZEROS_FOR_NUMBERS).build();
        SimpleModule numberModule = new SimpleModule("TomcatSafeNumbers");
        numberModule.addSerializer(Number.class, new SafeNumberSerializer());
        ObjectMapper mapper = new ObjectMapper(factory);
        mapper.registerModule(numberModule);
        mapper.enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
        return mapper;
    }

    /**
     * Serialize a value as JSON.
     *
     * @param value The value to serialize
     *
     * @return the JSON representation
     */
    public static String write(Object value) {
        try {
            return lowercaseUnicodeEscapes(MAPPER.writeValueAsString(value));
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Unable to write JSON", e);
        }
    }

    private static String lowercaseUnicodeEscapes(String json) {
        if (json.indexOf("\\u") < 0) {
            return json;
        }
        StringBuilder result = null;
        int copied = 0;
        int index = 0;
        while (index < json.length()) {
            if (json.charAt(index) != '\\') {
                index++;
                continue;
            }
            int slashes = index;
            while (index < json.length() && json.charAt(index) == '\\') {
                index++;
            }
            int slashCount = index - slashes;
            if ((slashCount & 1) == 1 && index + 4 < json.length() && json.charAt(index) == 'u' &&
                    isHex(json, index + 1)) {
                if (result == null) {
                    result = new StringBuilder(json.length());
                }
                result.append(json, copied, index + 1);
                for (int i = index + 1; i <= index + 4; i++) {
                    result.append(Character.toLowerCase(json.charAt(i)));
                }
                index += 5;
                copied = index;
            }
        }
        if (result == null) {
            return json;
        }
        result.append(json, copied, json.length());
        return result.toString();
    }

    private static boolean isHex(String value, int offset) {
        for (int i = offset; i < offset + 4; i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Escape a range using the JSON library's string serializer.
     *
     * @param input The source
     * @param offset The start offset
     * @param length The number of characters
     *
     * @return the escaped range, without surrounding quotation marks
     */
    public static String escape(CharSequence input, int offset, int length) {
        if (input == null) {
            return "";
        }
        if (offset < 0 || length < 0 || offset > input.length() - length) {
            throw new IndexOutOfBoundsException();
        }
        String quoted = write(input.subSequence(offset, offset + length).toString());
        return quoted.substring(1, quoted.length() - 1);
    }

    public static Object parse(String input, boolean nativeNumbers, boolean requireEof) throws IOException {
        return parse(new java.io.StringReader(input), nativeNumbers, requireEof);
    }

    public static Object parse(InputStream input, boolean nativeNumbers, boolean requireEof) throws IOException {
        try (JsonParser parser = MAPPER.getFactory().createParser(input)) {
            return parse(parser, nativeNumbers, requireEof);
        }
    }

    public static Object parse(Reader input, boolean nativeNumbers, boolean requireEof) throws IOException {
        try (JsonParser parser = MAPPER.getFactory().createParser(input)) {
            return parse(parser, nativeNumbers, requireEof);
        }
    }

    private static Object parse(JsonParser parser, boolean nativeNumbers, boolean requireEof) throws IOException {
        JsonNode root = MAPPER.readTree(parser);
        if (root == null) {
            throw new IOException("No JSON value was provided");
        }
        if (requireEof && parser.nextToken() != null) {
            throw new IOException("Unexpected content after the JSON value at " + parser.currentTokenLocation());
        }
        return convert(root, nativeNumbers);
    }

    private static Object convert(JsonNode node, boolean nativeNumbers) throws IOException {
        if (node.isObject()) {
            LinkedHashMap<String,Object> result = new LinkedHashMap<>();
            Iterator<Map.Entry<String,JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String,JsonNode> field = fields.next();
                result.put(normalizeKey(field.getKey()), convert(field.getValue(), nativeNumbers));
            }
            return result;
        }
        if (node.isArray()) {
            ArrayList<Object> result = new ArrayList<>(node.size());
            for (JsonNode child : node) {
                result.add(convert(child, nativeNumbers));
            }
            return result;
        }
        if (node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return Boolean.valueOf(node.booleanValue());
        }
        if (node.isIntegralNumber()) {
            BigInteger value = node.bigIntegerValue();
            if (nativeNumbers && value.bitLength() < 64) {
                return Long.valueOf(value.longValue());
            }
            return value;
        }
        if (node.isNumber()) {
            BigDecimal value = node.decimalValue();
            if (nativeNumbers) {
                return Double.valueOf(value.doubleValue());
            }
            return value;
        }
        throw new IOException("Unsupported JSON node type [" + node.getNodeType() + "]");
    }

    private static String normalizeKey(String key) throws IOException {
        if (!isJsonNumber(key)) {
            return key;
        }
        try {
            if (key.indexOf('.') >= 0 || key.indexOf('e') >= 0 || key.indexOf('E') >= 0) {
                return new BigDecimal(key).toString();
            }
            return new BigInteger(key).toString();
        } catch (NumberFormatException e) {
            throw new IOException("Invalid numeric object key [" + key + "]", e);
        }
    }

    private static boolean isJsonNumber(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        int length = value.length();
        int index = value.charAt(0) == '-' ? 1 : 0;
        if (index == length) {
            return false;
        }
        int integerStart = index;
        while (index < length && Character.isDigit(value.charAt(index))) {
            index++;
        }
        if (integerStart == index) {
            return false;
        }
        if (index < length && value.charAt(index) == '.') {
            index++;
            int fractionStart = index;
            while (index < length && Character.isDigit(value.charAt(index))) {
                index++;
            }
            if (fractionStart == index) {
                return false;
            }
        }
        if (index < length && (value.charAt(index) == 'e' || value.charAt(index) == 'E')) {
            index++;
            if (index < length && (value.charAt(index) == '+' || value.charAt(index) == '-')) {
                index++;
            }
            int exponentStart = index;
            while (index < length && Character.isDigit(value.charAt(index))) {
                index++;
            }
            if (exponentStart == index) {
                return false;
            }
        }
        return index == length;
    }

    private static final class SafeNumberSerializer extends JsonSerializer<Number> {
        @Override
        public void serialize(Number value, JsonGenerator generator, SerializerProvider serializers)
                throws IOException {
            if (value instanceof Double) {
                double number = value.doubleValue();
                if (!Double.isFinite(number)) {
                    generator.writeString(value.toString());
                    return;
                }
                if (number == Math.rint(number) && number >= Long.MIN_VALUE && number <= Long.MAX_VALUE) {
                    generator.writeNumber((long) number);
                    return;
                }
            } else if (value instanceof Float) {
                float number = value.floatValue();
                if (!Float.isFinite(number)) {
                    generator.writeString(value.toString());
                    return;
                }
                if (number == Math.rint(number) && number >= Long.MIN_VALUE && number <= Long.MAX_VALUE) {
                    generator.writeNumber((long) number);
                    return;
                }
            }
            String text = value.toString();
            if (isJsonNumber(text)) {
                generator.writeNumber(text);
            } else {
                generator.writeString(text);
            }
        }
    }
}
