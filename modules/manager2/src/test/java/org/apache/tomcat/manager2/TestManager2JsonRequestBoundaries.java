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

import org.junit.Assert;
import org.junit.Test;

import org.apache.catalina.startup.SimpleHttpClient;

/** Verifies that malformed and oversized JSON requests fail at the HTTP boundary. */
public class TestManager2JsonRequestBoundaries extends Manager2ConfigTestBase {

    @Test
    public void testMalformedAndOversizedRequests() throws Exception {
        setup();

        SimpleHttpClient client = new TestClient();
        client.setPort(getPort());
        client.connect();
        String token = loginAndGetToken(client, "manager1");

        request(client, "POST", MANAGER2 + "/api/config/child", token, "{\"parent\":", 400);
        Assert.assertTrue(client.getResponseBody().contains("BAD_JSON"));

        // A valid JSON document still reaches the API's semantic validation.
        request(client, "POST", MANAGER2 + "/api/config/child", token, "{}", 400);
        Assert.assertFalse(client.getResponseBody().contains("BAD_JSON"));

        // The declared request size is rejected before the servlet allocates a body buffer.
        request(client, "POST", MANAGER2 + "/api/config/child", token,
                " ".repeat(JsonInput.MAX_BYTES + 1), 413);
        java.util.Map<String,Object> error = (java.util.Map<String,Object>)
                new org.apache.tomcat.util.json.JSONParser(client.getResponseBody()).parseObject();
        Assert.assertEquals(Boolean.FALSE, error.get("ok"));
        Assert.assertEquals(413, ((Number) error.get("status")).intValue());
        Assert.assertEquals("REQUEST_TOO_LARGE", error.get("error"));

        client.disconnect();
    }


    @Test
    public void testMethodNotAllowedUsesTheSameErrorEnvelope() throws Exception {
        setup();
        SimpleHttpClient client = new TestClient();
        client.setPort(getPort());
        request(client, "POST", MANAGER2 + "/i18n", null, "", 405);
        java.util.Map<String,Object> error = (java.util.Map<String,Object>)
                new org.apache.tomcat.util.json.JSONParser(client.getResponseBody()).parseObject();
        Assert.assertEquals(Boolean.FALSE, error.get("ok"));
        Assert.assertEquals(405, ((Number) error.get("status")).intValue());
        Assert.assertEquals("METHOD_NOT_ALLOWED", error.get("error"));
        Assert.assertTrue(client.getResponseHeaders().stream().anyMatch(
                header -> header.equalsIgnoreCase("Allow: GET, HEAD")));
        client.disconnect();
    }
}
