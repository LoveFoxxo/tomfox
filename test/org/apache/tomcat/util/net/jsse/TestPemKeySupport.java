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
package org.apache.tomcat.util.net.jsse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import org.junit.Assert;
import org.junit.Test;

public class TestPemKeySupport {

    private static final byte[] MESSAGE = "Tomcat PEM interoperability".getBytes(StandardCharsets.UTF_8);


    @Test
    public void testRsaSignVerifyAndOaep() throws Exception {
        KeyPair keyPair = generate("RSA", 2048);
        PrivateKeyAsserts.assertRoundTrip(keyPair);

        byte[] signature = PemKeySupport.sign(keyPair.getPrivate(), MESSAGE);
        Assert.assertTrue(PemKeySupport.verify(keyPair.getPublic(), MESSAGE, signature));
        signature[0] ^= 1;
        Assert.assertFalse(PemKeySupport.verify(keyPair.getPublic(), MESSAGE, signature));

        byte[] encrypted = PemKeySupport.encrypt(keyPair.getPublic(), MESSAGE);
        Assert.assertArrayEquals(MESSAGE, PemKeySupport.decrypt(keyPair.getPrivate(), encrypted));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> PemKeySupport.encrypt(keyPair.getPublic(), new byte[191]));
    }


    @Test
    public void testEcSignVerifyAndEncryptionRejection() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = generator.generateKeyPair();
        PrivateKeyAsserts.assertRoundTrip(keyPair);

        byte[] signature = PemKeySupport.sign(keyPair.getPrivate(), MESSAGE);
        Assert.assertTrue(PemKeySupport.verify(keyPair.getPublic(), MESSAGE, signature));
        Assert.assertThrows(InvalidKeyException.class, () -> PemKeySupport.encrypt(keyPair.getPublic(), MESSAGE));
    }


    @Test
    public void testEd25519SignVerify() throws Exception {
        KeyPair keyPair = generate("Ed25519", -1);
        PrivateKeyAsserts.assertRoundTrip(keyPair);

        byte[] signature = PemKeySupport.sign(keyPair.getPrivate(), MESSAGE);
        Assert.assertTrue(PemKeySupport.verify(keyPair.getPublic(), MESSAGE, signature));
    }


    private static KeyPair generate(String algorithm, int keySize) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
        if (keySize > 0) {
            generator.initialize(keySize);
        }
        return generator.generateKeyPair();
    }


    private static final class PrivateKeyAsserts {
        private static void assertRoundTrip(KeyPair keyPair) throws Exception {
            String base64 = Base64.getMimeEncoder(64, new byte[] { '\n' })
                    .encodeToString(keyPair.getPrivate().getEncoded());
            String pem = "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
            PEMFile pemFile = new PEMFile("generated-" + keyPair.getPrivate().getAlgorithm(),
                    new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)), null, null);
            Assert.assertEquals(keyPair.getPrivate().getAlgorithm(), pemFile.getPrivateKey().getAlgorithm());
            byte[] signature = PemKeySupport.sign(pemFile.getPrivateKey(), MESSAGE);
            Assert.assertTrue(PemKeySupport.verify(keyPair.getPublic(), MESSAGE, signature));
        }
    }
}
