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
package org.apache.tomcat.manager2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.tomcat.util.json.JSONParser;


/** Request scoped JSON input handling. Instances are never shared between requests. */
final class JsonInput {

    static final int MAX_BYTES = 1024 * 1024;
    private static final int CHUNK_SIZE = 8192;


    static Map<String, Object> readObject(HttpServletRequest request) throws IOException {
        byte[] bytes = readLimited(request.getInputStream(), MAX_BYTES);
        if (bytes.length == 0) {
            return new LinkedHashMap<>();
        }

        final String body;
        try {
            body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw invalidJson(e);
        }
        if (body.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new JSONParser(body).parseObject();
        } catch (Exception e) {
            throw invalidJson(e);
        }
    }


    private static InvalidJsonException invalidJson(Exception cause) {
        return new InvalidJsonException(Strings.sm().getString("manager2.invalidJson", cause.getMessage()), cause);
    }


    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        // Content-Length is deliberately not used as an allocation size: it may be absent or false.
        ByteArrayOutputStream result = new ByteArrayOutputStream(Math.min(CHUNK_SIZE, limit));
        byte[] chunk = new byte[Math.min(CHUNK_SIZE, limit + 1)];
        int total = 0;
        while (total <= limit) {
            int count = input.read(chunk, 0, Math.min(chunk.length, limit + 1 - total));
            if (count < 0) {
                return result.toByteArray();
            }
            if (count == 0) {
                int value = input.read();
                if (value < 0) {
                    return result.toByteArray();
                }
                result.write(value);
                total++;
            } else {
                result.write(chunk, 0, count);
                total += count;
            }
        }
        throw new BodyTooLargeException(limit);
    }


    static final class InvalidJsonException extends IllegalArgumentException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        private InvalidJsonException(String message, Exception cause) {
            super(message, cause);
        }
    }


    static final class BodyTooLargeException extends IOException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        private BodyTooLargeException(int limit) {
            super("JSON request body exceeds " + limit + " bytes");
        }
    }


    private JsonInput() {
        // Utility class
    }
}
