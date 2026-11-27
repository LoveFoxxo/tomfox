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
package org.apache.tomcat.util.json;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.LinkedHashMap;

import org.apache.juli.JsonSupport;

/**
 * Compatibility facade for Tomcat's historical JSON parser. Parsing is performed by Jackson with the lenient syntax,
 * numeric types and nesting limits of the original API. Instances are stateful and not thread safe; the underlying
 * Jackson factories and mappers are safely shared.
 */
public class JSONParser {

    private Object input;
    private boolean nativeNumbers;

    public JSONParser(String s) {
        this.input = s;
    }

    public JSONParser(InputStream stream) {
        this.input = stream;
    }

    public JSONParser(InputStream stream, String encoding) {
        setInput(stream, encoding);
    }

    public JSONParser(Reader reader) {
        this.input = reader;
    }

    /**
     * Token-manager construction belonged to the former JavaCC implementation.
     *
     * @param tokenManager unused
     *
     * @deprecated Use a String, Reader or InputStream constructor.
     */
    @Deprecated
    public JSONParser(JSONParserTokenManager tokenManager) {
        throw new UnsupportedOperationException("Token-manager parsing is no longer supported");
    }

    public void setNativeNumbers(boolean value) {
        nativeNumbers = value;
    }

    public boolean getNativeNumbers() {
        return nativeNumbers;
    }

    public LinkedHashMap<String,Object> parseObject() throws ParseException {
        return requireObject(parse(true));
    }

    public ArrayList<Object> parseArray() throws ParseException {
        return requireArray(parse(true));
    }

    public Object parse() throws ParseException {
        return parse(true);
    }

    public LinkedHashMap<String,Object> object() throws ParseException {
        return requireObject(parse(false));
    }

    public ArrayList<Object> list() throws ParseException {
        return requireArray(parse(false));
    }

    public Object anything() throws ParseException {
        return parse(false);
    }

    public Object value() throws ParseException {
        return parse(false);
    }

    public Object nullValue() throws ParseException {
        Object result = parse(false);
        if (result != null) {
            throw new ParseException("Expected JSON null");
        }
        return null;
    }

    public Boolean booleanValue() throws ParseException {
        Object result = parse(false);
        if (result instanceof Boolean value) {
            return value;
        }
        throw new ParseException("Expected a JSON boolean");
    }

    public Number number() throws ParseException {
        Object result = parse(false);
        if (result instanceof Number value) {
            return value;
        }
        throw new ParseException("Expected a JSON number");
    }

    public String string() throws ParseException {
        Object result = parse(false);
        if (result instanceof String value) {
            return value;
        }
        throw new ParseException("Expected a JSON string");
    }

    public String doubleQuoteString() throws ParseException {
        return string();
    }

    public String singleQuoteString() throws ParseException {
        return string();
    }

    public String objectKey() throws ParseException {
        Object result = parse(false);
        return result == null ? null : result.toString();
    }

    public String symbol() throws ParseException {
        return string();
    }

    public void ReInit(InputStream stream) {
        input = stream;
    }

    public void ReInit(InputStream stream, String encoding) {
        setInput(stream, encoding);
    }

    public void ReInit(Reader reader) {
        input = reader;
    }

    /**
     * @param tokenManager unused
     *
     * @deprecated Use {@link #ReInit(Reader)} or an InputStream variant.
     */
    @Deprecated
    public void ReInit(JSONParserTokenManager tokenManager) {
        throw new UnsupportedOperationException("Token-manager parsing is no longer supported");
    }

    public void enable_tracing() {
        // No JavaCC trace remains.
    }

    public void disable_tracing() {
        // No JavaCC trace remains.
    }

    private void setInput(InputStream stream, String encoding) {
        if (encoding == null) {
            input = stream;
        } else {
            try {
                input = new InputStreamReader(stream, encoding);
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private Object parse(boolean requireEof) throws ParseException {
        try {
            if (input instanceof String value) {
                return JsonSupport.parse(value, nativeNumbers, requireEof);
            }
            if (input instanceof Reader value) {
                return JsonSupport.parse(value, nativeNumbers, requireEof);
            }
            if (input instanceof InputStream value) {
                return JsonSupport.parse(value, nativeNumbers, requireEof);
            }
            throw new ParseException("No JSON input has been configured");
        } catch (Exception e) {
            if (e instanceof ParseException parseException) {
                throw parseException;
            }
            throw new ParseException("Invalid JSON: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static LinkedHashMap<String,Object> requireObject(Object value) throws ParseException {
        if (value instanceof LinkedHashMap<?,?>) {
            return (LinkedHashMap<String,Object>) value;
        }
        throw new ParseException("Expected a JSON object");
    }

    @SuppressWarnings("unchecked")
    private static ArrayList<Object> requireArray(Object value) throws ParseException {
        if (value instanceof ArrayList<?>) {
            return (ArrayList<Object>) value;
        }
        throw new ParseException("Expected a JSON array");
    }
}
