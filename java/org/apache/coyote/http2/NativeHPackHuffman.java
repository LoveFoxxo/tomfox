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

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;


/** Optional native HPACK decoder. All malformed-input outcomes use the same exceptions as the Java decoder. */
final class NativeHPackHuffman {

    static final int MIN_NATIVE_INPUT = 64;

    private static final String LIBRARY_NAME = "tomfox-hpack";
    private static final String ENABLED_PROPERTY = "tomfox.hpack.native";

    private NativeHPackHuffman() {
    }


    static boolean initialise(int[] decodingTable) {
        if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"))) {
            return false;
        }
        try {
            String catalinaHome = System.getProperty("catalina.home");
            if (catalinaHome != null) {
                File library = new File(new File(catalinaHome, "bin"), System.mapLibraryName(LIBRARY_NAME));
                if (library.isFile()) {
                    System.load(library.getAbsolutePath());
                    return initialise0(decodingTable);
                }
            }
            System.loadLibrary(LIBRARY_NAME);
            return initialise0(decodingTable);
        } catch (UnsatisfiedLinkError | SecurityException e) {
            return false;
        }
    }


    static String decode(ByteBuffer data, int length) throws HpackException {
        int position = data.position();
        byte[] heapInput = data.hasArray() ? data.array() : null;
        int heapOffset = heapInput == null ? 0 : data.arrayOffset() + position;
        byte[] output = new byte[(int) ((length * 8L) / 5)];
        int result = decode0(heapInput, heapOffset, heapInput == null ? data : null, position, length, output);
        if (result >= 0) {
            data.position(position + length);
            return new String(output, 0, result, StandardCharsets.ISO_8859_1);
        }
        // Native errors discard their partial output. Replay malformed input through the reference decoder so buffer
        // consumption and the reported error remain identical even when native decoding stopped at a different bit.
        HPackHuffman.decodeJava(data, length, new StringBuilder());
        throw new HpackException(HPackHuffman.sm.getString("hpackhuffman.nativeFailure"));
    }


    private static native boolean initialise0(int[] decodingTable);


    private static native int decode0(byte[] heapInput, int heapOffset, ByteBuffer directInput,
            int directPosition, int inputLength, byte[] output);
}
