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
package org.apache.juli.logging;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Single default backend for Tomcat's logging anchors. Publishing and handler lifecycle remain delegated to JULI; this
 * class adds bounded logger management and an immutable recent-record view without retaining classes or exceptions.
 */
public final class LogHub {

    private static final String CAPACITY_PROPERTY = "org.apache.juli.logging.LogHub.recentCapacity";
    private static final int MAX_CAPACITY = 100_000;
    private static final int MAX_QUERY_RESULTS = 1000;
    private static final int MAX_MESSAGE_LENGTH = 16 * 1024;
    private static final int CAPACITY =
            Math.max(0, Math.min(MAX_CAPACITY, Integer.getInteger(CAPACITY_PROPERTY, 4096).intValue()));

    private static final Set<String> NAMES = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedDeque<LogEntry> RECENT = new ConcurrentLinkedDeque<>();
    private static final AtomicLong RECENT_COUNT = new AtomicLong();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final StackWalker STACK_WALKER = StackWalker.getInstance();

    private LogHub() {
    }


    /**
     * Last-resort structured reporting for logging bootstrap and handler failures. This deliberately bypasses JULI to
     * avoid recursive failures. Records are serialized as one write while holding a lock so concurrent errors stay intact.
     *
     * @param level the severity to report
     * @param source the source class name
     * @param method the source method name
     * @param message the failure message
     * @param thrown the associated failure, or {@code null}
     */
    public static void reportInternal(Level level, String source, String method, String message, Throwable thrown) {
        LogRecord record = new LogRecord(level, message == null ? "" : truncate(message, MAX_MESSAGE_LENGTH));
        record.setSourceClassName(source);
        record.setSourceMethodName(method);
        record.setThrown(thrown);
        String output;
        try {
            output = org.apache.juli.JsonFormatter.formatInternal(record);
        } catch (RuntimeException | LinkageError formattingFailure) {
            StringBuilder fallback = new StringBuilder(256);
            fallback.append("{\"time\":").append(quoteInternal(Instant.now().toString()));
            fallback.append(",\"level\":").append(quoteInternal(level.getLocalizedName()));
            fallback.append(",\"thread\":").append(quoteInternal(Thread.currentThread().getName()));
            fallback.append(",\"class\":").append(quoteInternal(source));
            fallback.append(",\"method\":").append(quoteInternal(method));
            fallback.append(",\"message\":").append(quoteInternal(record.getMessage()));
            if (thrown != null) {
                fallback.append(",\"throwable\":[").append(
                        quoteInternal(truncate(thrown.toString(), MAX_MESSAGE_LENGTH))).append(']');
            }
            fallback.append('}').append(System.lineSeparator());
            output = fallback.toString();
        }
        synchronized (LogHub.class) {
            System.err.print(output);
            System.err.flush();
        }
    }


    private static String quoteInternal(String value) {
        String input = String.valueOf(value);
        StringBuilder result = new StringBuilder(input.length() + 2).append('"');
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) {
                        appendUnicodeEscape(result, c);
                    } else if (Character.isSurrogate(c)) {
                        if (Character.isHighSurrogate(c) && i + 1 < input.length() &&
                                Character.isLowSurrogate(input.charAt(i + 1))) {
                            result.append(c).append(input.charAt(++i));
                        } else {
                            appendUnicodeEscape(result, c);
                        }
                    } else {
                        result.append(c);
                    }
                }
            }
        }
        return result.append('"').toString();
    }


    private static void appendUnicodeEscape(StringBuilder target, char c) {
        target.append("\\u");
        for (int shift = 12; shift >= 0; shift -= 4) {
            target.append(Character.forDigit((c >>> shift) & 0xf, 16));
        }
    }


    /**
     * Creates or looks up a logger and includes it in the manageable logger view.
     *
     * @param name the logger name
     *
     * @return the registered logger
     */
    public static Logger register(String name) {
        Objects.requireNonNull(name, "name");
        NAMES.add(name);
        return Logger.getLogger(name);
    }


    /**
     * Creates a logger if necessary and sets its level.
     *
     * @param name the logger name
     * @param level the explicit level
     */
    public static void upsert(String name, Level level) {
        Objects.requireNonNull(level, "level");
        register(name).setLevel(level);
    }


    /**
     * Deletes the explicit level and recent records for a logger, restoring inherited level configuration.
     *
     * @param name the logger name
     */
    public static void delete(String name) {
        Objects.requireNonNull(name, "name");
        Logger.getLogger(name).setLevel(null);
        NAMES.remove(name);
        clearRecent(name);
    }


    /**
     * Returns the state of a registered logger.
     *
     * @param name the logger name
     *
     * @return the state, or {@code null} if the logger is not registered
     */
    public static LogState get(String name) {
        if (!NAMES.contains(name)) {
            return null;
        }
        return stateOf(Logger.getLogger(name));
    }


    /**
     * Lists all registered loggers in name order.
     *
     * @return an immutable snapshot
     */
    public static List<LogState> list() {
        List<String> names = new ArrayList<>(NAMES);
        names.sort(Comparator.naturalOrder());
        List<LogState> result = new ArrayList<>(names.size());
        for (String name : names) {
            result.add(stateOf(Logger.getLogger(name)));
        }
        return List.copyOf(result);
    }


    /**
     * Returns newest records first. A blank prefix selects all loggers and a null minimum level selects all levels.
     *
     * @param loggerPrefix the logger name prefix, or {@code null} for all loggers
     * @param minimumLevel the minimum severity, or {@code null} for all severities
     * @param limit the maximum number of results, capped at 1000
     *
     * @return an immutable newest-first snapshot
     */
    public static List<LogEntry> query(String loggerPrefix, Level minimumLevel, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        int boundedLimit = Math.min(limit, MAX_QUERY_RESULTS);
        String prefix = loggerPrefix == null ? "" : loggerPrefix;
        Level minimum = minimumLevel == null ? Level.ALL : minimumLevel;
        List<LogEntry> result = new ArrayList<>(boundedLimit);
        for (LogEntry entry : RECENT) {
            if (entry.logger().startsWith(prefix) && entry.level().intValue() >= minimum.intValue()) {
                result.add(entry);
                if (result.size() == boundedLimit) {
                    break;
                }
            }
        }
        return List.copyOf(result);
    }


    /**
     * Deletes recent records matching the supplied prefix. A blank prefix clears the entire bounded view.
     *
     * @param loggerPrefix the logger name prefix, or {@code null} for all loggers
     */
    public static void clearRecent(String loggerPrefix) {
        String prefix = loggerPrefix == null ? "" : loggerPrefix;
        for (LogEntry entry : RECENT) {
            if (entry.logger().startsWith(prefix) && RECENT.remove(entry)) {
                RECENT_COUNT.decrementAndGet();
            }
        }
    }


    /**
     * Publishes through JULI and captures a bounded, text-only representation for management queries.
     *
     * @param logger the destination logger
     * @param level the record severity
     * @param message the message to publish
     * @param thrown the associated failure, or {@code null}
     */
    public static void publish(Logger logger, Level level, String message, Throwable thrown) {
        if (!logger.isLoggable(level)) {
            return;
        }
        String name = logger.getName();
        NAMES.add(name);
        StackTraceElement caller = caller();
        try {
            if (thrown == null) {
                logger.logp(level, caller.getClassName(), caller.getMethodName(), message);
            } else {
                logger.logp(level, caller.getClassName(), caller.getMethodName(), message, thrown);
            }
        } finally {
            append(new LogEntry(SEQUENCE.incrementAndGet(), Instant.now(), name, level,
                    truncate(message, MAX_MESSAGE_LENGTH),
                    thrown == null ? null : truncate(thrown.toString(), MAX_MESSAGE_LENGTH)));
        }
    }


    private static StackTraceElement caller() {
        return STACK_WALKER.walk(frames -> frames
                .filter(frame -> !frame.getClassName().startsWith("org.apache.juli.logging") &&
                        !frame.getClassName().startsWith("java.util.logging"))
                .findFirst()
                .map(StackWalker.StackFrame::toStackTraceElement)
                .orElseGet(() -> new StackTraceElement("unknown", "unknown", null, -1)));
    }


    private static void append(LogEntry entry) {
        if (CAPACITY == 0) {
            return;
        }
        RECENT.addFirst(entry);
        RECENT_COUNT.incrementAndGet();
        while (RECENT_COUNT.get() > CAPACITY) {
            if (RECENT.pollLast() == null) {
                break;
            }
            RECENT_COUNT.decrementAndGet();
        }
    }


    private static LogState stateOf(Logger logger) {
        Level effective = logger.getLevel();
        Logger current = logger;
        while (effective == null && current.getParent() != null) {
            current = current.getParent();
            effective = current.getLevel();
        }
        Handler[] handlers = logger.getHandlers();
        return new LogState(logger.getName(), logger.getLevel(), effective == null ? Level.ALL : effective,
                handlers.length);
    }


    private static String truncate(String value, int maximum) {
        if (value == null || value.length() <= maximum) {
            return value;
        }
        return value.substring(0, maximum);
    }


    /** Immutable management view of one log record. */
    public record LogEntry(long sequence, Instant timestamp, String logger, Level level, String message,
            String throwable) {
    }


    /** Immutable management view of one logger's effective configuration. */
    public record LogState(String name, Level configuredLevel, Level effectiveLevel, int handlerCount) {
    }
}
