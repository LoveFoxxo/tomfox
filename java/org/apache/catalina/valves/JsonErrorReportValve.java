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
package org.apache.catalina.valves;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.coyote.ActionCode;
import org.apache.juli.JsonSupport;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.res.StringManager;

/** Implementation of a Valve that outputs error JSON. */
public class JsonErrorReportValve extends ErrorReportValve {

    public JsonErrorReportValve() {
        super();
    }

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        int statusCode = response.getStatus();
        if (statusCode < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            return;
        }
        AtomicBoolean result = new AtomicBoolean(false);
        response.getCoyoteResponse().action(ActionCode.IS_IO_ALLOWED, result);
        if (!result.get()) {
            return;
        }

        Map<String,Object> json = new LinkedHashMap<>();
        json.put("ok", Boolean.FALSE);
        json.put("status", Integer.valueOf(statusCode));
        json.put("error", "HTTP_" + statusCode);
        json.put("message", "");
        if (isShowReport()) {
            StringManager smClient = StringManager.getManager(Constants.Package, request.getLocales());
            response.setLocale(smClient.getLocale());
            json.put("type", throwable == null ? "Status Report" : "Exception Report");
            String message = response.getMessage();
            if (message == null && throwable != null) {
                message = throwable.getMessage();
            }
            if (message == null) {
                message = "";
            }
            String reason = null;
            String description = null;
            try {
                reason = smClient.getString("http." + statusCode + ".reason");
                description = smClient.getString("http." + statusCode + ".desc");
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
            }
            if (reason == null || description == null) {
                reason = smClient.getString("errorReportValve.unknownReason");
                description = smClient.getString("errorReportValve.noDescription");
            }
            json.put("message", message);
            json.put("reason", reason);
            json.put("description", description);
            if (throwable != null) {
                json.put("throwable", createTrace(throwable));
            }
        }

        try {
            try {
                response.setContentType("application/json");
                response.setCharacterEncoding("utf-8");
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                if (container.getLogger().isDebugEnabled()) {
                    container.getLogger().debug(sm.getString("errorReportValve.contentTypeFail"), t);
                }
            }
            Writer writer = response.getReporter();
            if (writer != null) {
                writer.write(JsonSupport.write(json));
                response.finishResponse();
            }
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            // Ignore: the client connection or response state prevents an error report.
        }
    }

    private static List<String> createTrace(Throwable throwable) {
        List<String> trace = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int loops = 0;
        while (throwable != null && loops < 10 && seen.add(throwable)) {
            trace.add(throwable.toString());
            StackTraceElement[] elements = throwable.getStackTrace();
            int pos = elements.length;
            for (int i = elements.length - 1; i >= 0; i--) {
                if (elements[i].getClassName().startsWith("org.apache.catalina.core.ApplicationFilterChain") &&
                        elements[i].getMethodName().equals("doFilter")) {
                    pos = i;
                    break;
                }
            }
            for (int i = 0; i < pos; i++) {
                if (!elements[i].getClassName().startsWith("org.apache.catalina.core.")) {
                    trace.add(" " + elements[i]);
                }
            }
            throwable = throwable.getCause();
            loops++;
        }
        return trace;
    }
}
