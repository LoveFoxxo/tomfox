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
package org.apache.coyote.http2;

import java.nio.ByteBuffer;
import java.util.Random;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;


public class TestHPackHuffman {

    /*
     * This specific String exposed an edge case that triggered an unexpected HPACK parsing failure.
     */
    @Test
    public void testValueLeftBrace() throws Exception {
        ByteBuffer buf = ByteBuffer.allocate(10);
        String data = "x-value{";
        HPackHuffman.encode(buf, data);

        buf.flip();
        // Remove the header byte (in Tomcat this is parsed before the bytes are passed to the HPACK decoder)
        buf.get();

        StringBuilder target = new StringBuilder();
        HPackHuffman.decode(buf, buf.remaining(), target);

        Assert.assertEquals("Value changed after encode/decode roundtrip", data, target.toString());
    }


    @Test
    public void testNativeMatchesJavaForArbitraryInput() throws Exception {
        Assume.assumeTrue(HPackHuffman.isNativeDecoderEnabled());
        Random random = new Random(0x746f6d666f78L);
        for (int iteration = 0; iteration < 500; iteration++) {
            byte[] input = new byte[64 + random.nextInt(449)];
            random.nextBytes(input);
            if (iteration == 0) {
                java.util.Arrays.fill(input, (byte) 0xff);
            } else if (iteration == 1) {
                java.util.Arrays.fill(input, (byte) 0);
            }
            DecodeResult javaResult = decode(input, false, iteration % 2 == 0);
            DecodeResult nativeResult = decode(input, true, iteration % 2 == 0);
            Assert.assertEquals("Different failure at iteration " + iteration,
                    javaResult.error, nativeResult.error);
            Assert.assertEquals("Different output at iteration " + iteration,
                    javaResult.value, nativeResult.value);
            Assert.assertEquals("Different input consumption at iteration " + iteration,
                    javaResult.position, nativeResult.position);
            if (javaResult.error == null) {
                Assert.assertEquals(input.length, javaResult.position);
            }
        }
    }


    @Test
    public void testNativeEncodedRoundTrips() throws Exception {
        Assume.assumeTrue(HPackHuffman.isNativeDecoderEnabled());
        Random random = new Random(42);
        for (int iteration = 0; iteration < 50; iteration++) {
            int length = 512 + random.nextInt(512);
            StringBuilder value = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                value.append((char) (32 + random.nextInt(95)));
            }
            ByteBuffer encodedBuffer = ByteBuffer.allocate(length * 2 + 16);
            Assert.assertTrue(HPackHuffman.encode(encodedBuffer, value.toString()));
            encodedBuffer.flip();
            encodedBuffer.get();
            int encodedLength = Hpack.decodeInteger(encodedBuffer, 7);
            byte[] encoded = new byte[encodedLength];
            encodedBuffer.get(encoded);

            DecodeResult expected = decode(encoded, false, false);
            DecodeResult heap = decode(encoded, true, false);
            DecodeResult direct = decode(encoded, true, true);
            Assert.assertEquals("Heap decoder failure at iteration " + iteration, expected.error, heap.error);
            Assert.assertEquals("Direct decoder failure at iteration " + iteration, expected.error, direct.error);
            Assert.assertEquals(value.toString(), expected.value);
            Assert.assertEquals(value.toString(), heap.value);
            Assert.assertEquals(value.toString(), direct.value);
            Assert.assertEquals(expected.position, heap.position);
            Assert.assertEquals(expected.position, direct.position);
        }
    }


    private static DecodeResult decode(byte[] input, boolean nativeDecoder, boolean direct) {
        final int offset = 7;
        ByteBuffer buffer = direct ? ByteBuffer.allocateDirect(input.length + offset + 3) :
                ByteBuffer.allocate(input.length + offset + 3);
        buffer.position(offset);
        buffer.put(input);
        buffer.limit(offset + input.length);
        buffer.position(offset);
        StringBuilder target = new StringBuilder();
        try {
            if (nativeDecoder) {
                HPackHuffman.decode(buffer, input.length, target);
            } else {
                HPackHuffman.decodeJava(buffer, input.length, target);
            }
            return new DecodeResult(target.toString(), null, buffer.position() - offset);
        } catch (HpackException e) {
            return new DecodeResult(null, e.getMessage(), buffer.position() - offset);
        }
    }


    private record DecodeResult(String value, String error, int position) {
    }
}
