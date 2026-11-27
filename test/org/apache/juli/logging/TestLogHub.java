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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.Assert;
import org.junit.Test;

public class TestLogHub {

    @Test
    public void testLoggerCrudAndRecentRecords() {
        String name = TestLogHub.class.getName() + ".crud." + UUID.randomUUID();
        Logger logger = LogHub.register(name);
        logger.setUseParentHandlers(false);
        try {
            DirectJDKLog log = new DirectJDKLog(name);
            LogHub.upsert(name, Level.INFO);
            log.info("visible");
            log.debug("hidden");

            List<LogHub.LogEntry> entries = LogHub.query(name, Level.ALL, 10);
            Assert.assertEquals(1, entries.size());
            Assert.assertEquals("visible", entries.get(0).message());
            Assert.assertEquals(name, LogHub.get(name).name());
            Assert.assertTrue(LogHub.list().stream().anyMatch(state -> name.equals(state.name())));

            LogHub.upsert(name, Level.FINE);
            log.debug("also-visible");
            Assert.assertEquals(2, LogHub.query(name, Level.ALL, 10).size());
            Assert.assertEquals(2, LogHub.query(name, Level.FINE, 10).size());

            LogHub.clearRecent(name);
            Assert.assertTrue(LogHub.query(name, Level.ALL, 10).isEmpty());
            LogHub.delete(name);
            Assert.assertNull(LogHub.get(name));
            Assert.assertNull(logger.getLevel());
        } finally {
            LogHub.delete(name);
            logger.setUseParentHandlers(true);
        }
    }


    @Test
    public void testConcurrentPublishing() throws Exception {
        String name = TestLogHub.class.getName() + ".concurrent." + UUID.randomUUID();
        Logger logger = LogHub.register(name);
        logger.setUseParentHandlers(false);
        LogHub.upsert(name, Level.INFO);
        int threads = 4;
        int perThread = 250;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int i = 0; i < threads; i++) {
                int thread = i;
                executor.execute(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        DirectJDKLog log = new DirectJDKLog(name);
                        for (int j = 0; j < perThread; j++) {
                            log.info("message-" + thread + '-' + j);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            Assert.assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            Assert.assertTrue(done.await(30, TimeUnit.SECONDS));

            List<LogHub.LogEntry> entries = LogHub.query(name, Level.INFO, 2000);
            Assert.assertEquals(threads * perThread, entries.size());
            Set<Long> sequences = new HashSet<>();
            for (LogHub.LogEntry entry : entries) {
                Assert.assertTrue(sequences.add(entry.sequence()));
            }
        } finally {
            executor.shutdownNow();
            LogHub.delete(name);
            logger.setUseParentHandlers(true);
        }
    }


    @Test
    public void testInternalErrorsUseTheSameJsonEnvelope() throws Exception {
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try {
            System.setErr(new java.io.PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8));
            LogHub.reportInternal(Level.SEVERE, "test.Source", "failure", "line one\nline \"two\"",
                    new IllegalStateException("broken"));
        } finally {
            System.setErr(original);
        }
        String output = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        Assert.assertEquals(1, output.strip().split("\\R").length);
        @SuppressWarnings("unchecked")
        java.util.Map<String,Object> json = (java.util.Map<String,Object>) org.apache.juli.JsonSupport.parse(
                output, true, true);
        Assert.assertEquals("SEVERE", json.get("level"));
        Assert.assertEquals("test.Source", json.get("class"));
        Assert.assertEquals("failure", json.get("method"));
        Assert.assertEquals("line one\nline \"two\"", json.get("message"));
        Assert.assertTrue(json.get("throwable") instanceof List);
        Assert.assertTrue(json.get("throwable").toString().contains("broken"));
    }
}
