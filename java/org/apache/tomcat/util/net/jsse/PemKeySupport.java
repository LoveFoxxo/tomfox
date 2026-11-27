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

import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAKey;
import java.security.spec.MGF1ParameterSpec;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

import org.apache.tomcat.util.res.StringManager;

/**
 * Thread-safe convenience operations for keys loaded from a {@link PEMFile}. Each operation uses a new JCA primitive;
 * callers retain ownership of key and data objects.
 */
public final class PemKeySupport {

    private static final StringManager sm = StringManager.getManager(PemKeySupport.class);

    private static final String RSA = "RSA";
    private static final String RSA_OAEP = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final OAEPParameterSpec OAEP_SHA256 =
            new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    private PemKeySupport() {
    }


    public static byte[] sign(PrivateKey privateKey, byte[] data) throws GeneralSecurityException {
        Objects.requireNonNull(privateKey, "privateKey");
        Objects.requireNonNull(data, "data");
        Signature signer = Signature.getInstance(signatureAlgorithm(privateKey));
        signer.initSign(privateKey);
        signer.update(data);
        return signer.sign();
    }


    public static boolean verify(PublicKey publicKey, byte[] data, byte[] signature) throws GeneralSecurityException {
        Objects.requireNonNull(publicKey, "publicKey");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(signature, "signature");
        Signature verifier = Signature.getInstance(signatureAlgorithm(publicKey));
        verifier.initVerify(publicKey);
        verifier.update(data);
        return verifier.verify(signature);
    }


    /**
     * Encrypts one payload with RSA-OAEP using SHA-256 for both OAEP and MGF1. This is intentionally a single-block RSA
     * operation, not a custom hybrid-encryption format. Use a standard envelope protocol for larger payloads.
     *
     * @param publicKey the RSA public key
     * @param plaintext the payload, limited by the RSA modulus size
     *
     * @return the encrypted payload
     *
     * @throws GeneralSecurityException if encryption fails
     */
    public static byte[] encrypt(PublicKey publicKey, byte[] plaintext) throws GeneralSecurityException {
        Objects.requireNonNull(publicKey, "publicKey");
        Objects.requireNonNull(plaintext, "plaintext");
        Cipher cipher = rsaCipher(Cipher.ENCRYPT_MODE, publicKey);
        if (publicKey instanceof RSAKey rsaKey) {
            int maximum = (rsaKey.getModulus().bitLength() + 7) / 8 - 66;
            if (plaintext.length > maximum) {
                throw new IllegalArgumentException(sm.getString("pemKeySupport.payloadTooLarge",
                        Integer.valueOf(plaintext.length), Integer.valueOf(maximum)));
            }
        }
        return cipher.doFinal(plaintext);
    }


    public static byte[] decrypt(PrivateKey privateKey, byte[] ciphertext) throws GeneralSecurityException {
        Objects.requireNonNull(privateKey, "privateKey");
        Objects.requireNonNull(ciphertext, "ciphertext");
        return rsaCipher(Cipher.DECRYPT_MODE, privateKey).doFinal(ciphertext);
    }


    private static Cipher rsaCipher(int mode, Key key) throws GeneralSecurityException {
        if (!RSA.equalsIgnoreCase(key.getAlgorithm())) {
            throw new InvalidKeyException(sm.getString("pemKeySupport.encryptionRequiresRsa", key.getAlgorithm()));
        }
        Cipher cipher = Cipher.getInstance(RSA_OAEP);
        cipher.init(mode, key, OAEP_SHA256);
        return cipher;
    }


    private static String signatureAlgorithm(Key key) throws InvalidKeyException {
        String algorithm = key.getAlgorithm();
        return switch (algorithm) {
            case "RSA" -> "SHA256withRSA";
            case "EC", "ECDSA" -> "SHA256withECDSA";
            case "DSA" -> "SHA256withDSA";
            case "Ed25519", "Ed448", "EdDSA" -> algorithm;
            default -> throw new InvalidKeyException(
                    sm.getString("pemKeySupport.unsupportedSignatureAlgorithm", algorithm));
        };
    }
}
