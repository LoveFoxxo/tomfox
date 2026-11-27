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

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;

/** Formats a complete log record as one JSON document per line. */
public class JsonFormatter extends OneLineFormatter {

    private static final String DEFAULT_TIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSX";

    public JsonFormatter() {
        String timeFormat = LogManager.getLogManager().getProperty(JsonFormatter.class.getName() + ".timeFormat");
        if (timeFormat == null) {
            timeFormat = DEFAULT_TIME_FORMAT;
        }
        setTimeFormat(timeFormat);
    }

    @Override
    public String format(LogRecord record) {
        StringBuilder timestamp = new StringBuilder();
        addTimestamp(timestamp, record.getMillis());
        return format(record, timestamp.toString(), String.valueOf(resolveThreadName(record)));
    }


    /**
     * Formats bootstrap and handler failures without consulting the LogManager again.
     *
     * @param record the internal failure record
     *
     * @return one complete JSON line
     */
    public static String formatInternal(LogRecord record) {
        return format(record, java.time.Instant.ofEpochMilli(record.getMillis()).toString(),
                Thread.currentThread().getName());
    }


    private static String format(LogRecord record, String timestamp, String threadName) {
        Map<String,Object> json = new LinkedHashMap<>();
        json.put("time", timestamp);
        json.put("level", record.getLevel().getName());
        json.put("thread", threadName);
        json.put("class", String.valueOf(record.getSourceClassName()));
        json.put("method", String.valueOf(record.getSourceMethodName()));
        String message = formatRecordMessage(record);
        json.put("message", message == null ? "" : message);

        Throwable throwable = record.getThrown();
        if (throwable != null) {
            List<String> trace = new ArrayList<>();
            Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            do {
                if (!seen.add(throwable)) {
                    trace.add("[Circular reference] " + throwable);
                    break;
                }
                trace.add(throwable.toString());
                for (StackTraceElement element : throwable.getStackTrace()) {
                    trace.add(" " + element);
                }
                throwable = throwable.getCause();
            } while (throwable != null);
            json.put("throwable", trace);
        }
        return JsonSupport.write(json) + System.lineSeparator();
    }


    private static String formatRecordMessage(LogRecord record) {
        String format = record.getMessage();
        java.util.ResourceBundle bundle = record.getResourceBundle();
        if (bundle != null && format != null) {
            try {
                format = bundle.getString(format);
            } catch (java.util.MissingResourceException e) {
                // Match java.util.logging.Formatter: retain the original message key.
            }
        }
        Object[] parameters = record.getParameters();
        if (format == null || parameters == null || parameters.length == 0) {
            return format;
        }
        try {
            return java.text.MessageFormat.format(format, parameters);
        } catch (IllegalArgumentException e) {
            return format;
        }
    }

    /** Compatibility entry point for callers that only need JSON string escaping. */
    public static class JSONFilter {

        public static String escape(String input) {
            return JsonSupport.escape(input, 0, input == null ? 0 : input.length());
        }

        public static CharSequence escape(CharSequence input, final int off, final int length) {
            return JsonSupport.escape(input, off, length);
        }

        private JSONFilter() {
        }
    }
}
