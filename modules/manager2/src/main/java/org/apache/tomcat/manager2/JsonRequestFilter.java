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

import java.io.IOException;
import java.io.Serial;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


/** Rejects oversized JSON requests before or while an API servlet reads the body. */
public class JsonRequestFilter extends HttpFilter {

    @Serial
    private static final long serialVersionUID = 1L;


    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase(java.util.Locale.ENGLISH).startsWith("multipart/")) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > JsonInput.MAX_BYTES) {
            requestTooLarge(request, response);
            return;
        }
        try {
            chain.doFilter(request, response);
        } catch (JsonInput.InvalidJsonException e) {
            if (response.isCommitted()) {
                throw e;
            }
            response.reset();
            Api.error(response, HttpServletResponse.SC_BAD_REQUEST, "BAD_JSON", e.getMessage());
            return;
        } catch (IOException | ServletException e) {
            if (isBodyTooLarge(e) && !response.isCommitted()) {
                response.reset();
                requestTooLarge(request, response);
                return;
            }
            throw e;
        }
    }


    private static void requestTooLarge(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Api.error(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "REQUEST_TOO_LARGE",
                Strings.manager(request).getString("manager2.requestTooLarge", Long.valueOf(JsonInput.MAX_BYTES)));
    }


    private static boolean isBodyTooLarge(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof JsonInput.BodyTooLargeException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
