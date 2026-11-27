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

public class TestManager2ReadOnlyManagement extends Manager2WebappTestBase {

    @Test
    public void testReadsRemainAvailableAndWritesAreRejected() throws Exception {
        setup();
        setManagerWritesAllowed(false);

        SimpleHttpClient client = new TestClient();
        client.setPort(getPort());
        client.connect();
        String token = loginAndGetToken(client, "manager1");

        request(client, "GET", MANAGER2 + "/api/config/tree", null, null, 200);
        Assert.assertTrue(client.getResponseBody().contains("tree"));

        request(client, "POST", MANAGER2 + "/api/config/child", token, "{}", 405);
        Assert.assertTrue(client.getResponseBody().contains("READ_ONLY"));
        Assert.assertEquals("GET, HEAD", client.getResponseHeader("Allow"));

        request(client, "DELETE", MANAGER2 + "/api/apps/testapp", token, null, 405);
        Assert.assertTrue(client.getResponseBody().contains("READ_ONLY"));
        client.disconnect();
    }
}
