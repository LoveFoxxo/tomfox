/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <jni.h>
#include <stdint.h>
#include <string.h>

#define TOMFOX_HPACK_NODES 256
#define TOMFOX_HPACK_LOW_TERMINAL UINT32_C(0x00008000)
#define TOMFOX_HPACK_HIGH_TERMINAL UINT32_C(0x80000000)
#define TOMFOX_HPACK_MASK UINT32_C(0x00007fff)

#define TOMFOX_HPACK_ERROR_EOS (-1)
#define TOMFOX_HPACK_ERROR_PADDING (-2)
#define TOMFOX_HPACK_ERROR_END (-3)
#define TOMFOX_HPACK_ERROR_INVALID (-4)

static const uint32_t TOMFOX_HPACK_TABLE[TOMFOX_HPACK_NODES] = {
#include "hpack_table.inc"
};

JNIEXPORT jboolean JNICALL
Java_org_apache_coyote_http2_NativeHPackHuffman_initialise0(
        JNIEnv *env, jclass type, jintArray java_table) {
    jint observed[TOMFOX_HPACK_NODES];
    (void) type;
    if (java_table == NULL ||
            (*env)->GetArrayLength(env, java_table) != TOMFOX_HPACK_NODES) {
        return JNI_FALSE;
    }
    (*env)->GetIntArrayRegion(env, java_table, 0, TOMFOX_HPACK_NODES, observed);
    if ((*env)->ExceptionCheck(env)) {
        return JNI_FALSE;
    }
    return memcmp(observed, TOMFOX_HPACK_TABLE, sizeof(observed)) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_org_apache_coyote_http2_NativeHPackHuffman_decode0(JNIEnv *env, jclass type,
        jbyteArray heap_input, jint heap_offset, jobject direct_input, jint direct_position,
        jint input_length, jbyteArray output) {
    const uint8_t *input = NULL;
    jbyte *heap_elements = NULL;
    jbyte *output_elements = NULL;
    jsize output_capacity = 0;
    jint output_length = 0;
    jint result = TOMFOX_HPACK_ERROR_INVALID;
    uint32_t tree_position = 0;
    int eos_bits = 1;
    int eos_bit_count = 0;
    jint byte_index;
    (void) type;

    if (direct_position < 0 || heap_offset < 0 || input_length < 0 || output == NULL) {
        goto cleanup;
    }
    output_capacity = (*env)->GetArrayLength(env, output);
    if ((uint64_t) output_capacity < ((uint64_t) input_length * 8U) / 5U) {
        goto cleanup;
    }
    if (output_capacity > 0) {
        output_elements = (*env)->GetByteArrayElements(env, output, NULL);
        if (output_elements == NULL) {
            goto cleanup;
        }
    }

    if (heap_input != NULL) {
        jsize heap_length = (*env)->GetArrayLength(env, heap_input);
        if (heap_offset > heap_length || input_length > heap_length - heap_offset) {
            goto cleanup;
        }
        if (input_length > 0) {
            heap_elements = (*env)->GetByteArrayElements(env, heap_input, NULL);
            if (heap_elements == NULL) {
                result = TOMFOX_HPACK_ERROR_INVALID;
                goto cleanup;
            }
            input = (const uint8_t *) heap_elements + heap_offset;
        }
    } else {
        uint8_t *direct = (uint8_t *) (*env)->GetDirectBufferAddress(env, direct_input);
        jlong direct_capacity = (*env)->GetDirectBufferCapacity(env, direct_input);
        if (direct == NULL || direct_capacity < 0 || direct_position > direct_capacity ||
                input_length > direct_capacity - direct_position) {
            goto cleanup;
        }
        input = direct + direct_position;
    }

    for (byte_index = 0; byte_index < input_length; byte_index++) {
        uint8_t current = input[byte_index];
        int bit_position;
        for (bit_position = 7; bit_position >= 0; bit_position--) {
            uint32_t value;
            int one = ((current >> bit_position) & 1U) != 0;
            if (tree_position >= TOMFOX_HPACK_NODES) {
                result = TOMFOX_HPACK_ERROR_INVALID;
                goto cleanup;
            }
            value = TOMFOX_HPACK_TABLE[tree_position];
            if (!one) {
                if ((value & TOMFOX_HPACK_LOW_TERMINAL) == 0) {
                    tree_position = value & TOMFOX_HPACK_MASK;
                    eos_bits = 0;
                    eos_bit_count = 0;
                } else {
                    if (output_length >= output_capacity) {
                        result = TOMFOX_HPACK_ERROR_INVALID;
                        goto cleanup;
                    }
                    output_elements[output_length++] = (jbyte) (value & TOMFOX_HPACK_MASK);
                    tree_position = 0;
                    eos_bits = 1;
                    eos_bit_count = 0;
                }
            } else {
                if (eos_bits) {
                    eos_bit_count++;
                }
                if ((value & TOMFOX_HPACK_HIGH_TERMINAL) == 0) {
                    tree_position = (value >> 16) & TOMFOX_HPACK_MASK;
                } else {
                    if (eos_bit_count != 0) {
                        result = TOMFOX_HPACK_ERROR_EOS;
                        goto cleanup;
                    }
                    if (output_length >= output_capacity) {
                        result = TOMFOX_HPACK_ERROR_INVALID;
                        goto cleanup;
                    }
                    output_elements[output_length++] =
                            (jbyte) ((value >> 16) & TOMFOX_HPACK_MASK);
                    tree_position = 0;
                    eos_bits = 1;
                    eos_bit_count = 0;
                }
            }
        }
    }

    if (eos_bit_count > 7) {
        result = TOMFOX_HPACK_ERROR_PADDING;
    } else if (!eos_bits) {
        result = TOMFOX_HPACK_ERROR_END;
    } else {
        result = output_length;
    }

cleanup:
    if (heap_elements != NULL) {
        (*env)->ReleaseByteArrayElements(env, heap_input, heap_elements, JNI_ABORT);
    }
    if (output_elements != NULL) {
        (*env)->ReleaseByteArrayElements(env, output, output_elements, result >= 0 ? 0 : JNI_ABORT);
    }
    return result;
}
