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


/** Restricts the management API to read-only requests before any request body is consumed. */
public class ReadOnlyManagementFilter extends HttpFilter {

    static final String ALLOW_WRITES_ATTRIBUTE = "org.apache.tomcat.manager2.allowManagementWrites";

    @Serial
    private static final long serialVersionUID = 1L;


    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String method = request.getMethod();
        boolean writesExplicitlyAllowed = Boolean.TRUE.equals(
                getServletContext().getAttribute(ALLOW_WRITES_ATTRIBUTE));
        if (writesExplicitlyAllowed || "GET".equals(method) || "HEAD".equals(method)) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Allow", "GET, HEAD");
        Api.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "READ_ONLY",
                Strings.sm().getString("manager2.readOnly"));
    }
}
