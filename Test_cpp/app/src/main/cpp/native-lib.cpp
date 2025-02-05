/* Edge Impulse Linux SDK
 * Copyright (c) 2021 EdgeImpulse Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

#include <jni.h>
#include <string>
#include <stdio.h>
#include "vector"
#include "edge-impulse-sdk/classifier/ei_run_classifier.h"

std::vector<float> raw_features = { -19.5937, 19.5839, -19.5839, -19.5937, 19.5839, -19.5839, -19.5937, 17.6814, -19.5839, -19.5937, 10.2578, -14.9453, -19.5937, 19.5839, 15.9162, -19.4662, 19.5839, 19.5937, -6.1586, -17.0440, 19.5937, 0.1373, -19.5937, 19.5937, -0.8924, -19.5937, 19.5937, -9.1006, -9.7086, 15.8770, -19.5937, -2.9616, 3.8050, -19.5348, -3.7952, -19.5839, -19.5937, -8.1787, -19.5839, -19.5937, -14.4256, -19.5839, -19.5937, 1.5396, -19.5839, -18.2992, -4.4130, -19.5839, -12.5035, -16.0437, -19.3583, -7.3158, -19.5937, -5.6879, -3.5696, -19.5937, 14.0823, -0.4021, -19.5937, 19.5937, 2.7361, -19.5937, 19.5937, -3.0597, -17.6029, 19.5937, -15.6220, 2.1967, 19.5937, -19.1524, 19.5447, 17.4068, -19.5937, 4.4130, -7.5511, -19.5937, 18.9170, -19.5839, -19.5937, 16.1712, -19.5839, -19.5937, -0.9611, -19.5839, -19.5937, 13.4841, -19.5839, -19.5937, 19.5839, -19.5839, -19.5545, 14.0039, -17.2303, -13.8568, 12.6604, 1.0689, -2.6478, 10.1401, 15.1905, 7.3158, -18.8386, 19.5937, 8.9535, -19.5937, 19.5937, 3.9030, -19.5937, 19.5937, -3.1381, -19.5937, 1.1768, -9.8753, -19.5937, -19.5839, -15.6906, -19.5937, -19.5839, -19.5937, -2.9420, -19.5839, -19.5937, 10.1891, -19.5839, -19.5937, 6.9627, -19.5839, -19.5937, 12.9938, -19.5839, -19.1818, 13.9058, -19.5839, -16.6713, 8.9829, -18.3875, -13.7685, 16.9361, -4.9720, -7.2471, -1.1278, 16.8086, 1.0689, -18.4267, 19.5937, -0.1667, -17.8383, 19.5937, -2.4124, -5.5408, 11.6797, -8.5318, -5.6781, 3.6187, -14.6806, 3.8442, -11.1109, -17.1518, -4.9524, -19.5643, -15.8672, -17.1616, -19.5839, -8.9142, -19.5937, -19.5839, -2.9322, -19.5937, -19.5839, 1.9221, -13.3174, -19.5839, 2.9028, -14.6119, -12.9546, 3.7756, -17.8677, 3.9717, 1.7750, -10.7873, 19.5937, 0.3432, -11.0815, 19.5937, -2.5988, -14.7688, 19.5937, -12.8859, -12.1112, 11.5130, -18.0835, 1.1768, -4.1482, -19.2603, 5.0897, -11.6797, -19.5937, 14.5335, -19.5839, -19.5937, 18.2894, -19.5839, -19.5937, 12.4544, -19.5839, -19.5937, 11.1207, -19.5839, -19.5937, 9.3359, -19.5839, -19.5839, 3.3931, -19.5839, -13.6312, -16.5046, -18.2011, -9.5419, -19.5937, -11.3169, -6.1488, -19.5937, 1.6181, 0.4707, -19.5937, 17.4068, 1.3337, -19.5937, 19.5937, 5.4231, -19.5937, 19.5937, -4.4424, -1.4612, 19.5937, -10.9148, 6.9431, 19.5937, -16.1614, 19.3583, 4.6974, -19.5937, 12.6310, -9.2183, -19.5937, 5.9821, -19.5839, -19.5937, 1.8437, -19.5839, -19.5937, -6.7568, -19.5839, -19.5937, 0.8532, -19.5839, -19.5937, -0.6669, -19.5839, -15.4945, 3.3735, -19.5839, -9.0810, 1.7652, -16.2202, -7.9336, -8.2180, -10.2774, -6.2566, -19.3976, -3.3146, -3.4225, -10.6402, 10.5421, 3.8050, -18.6719, 19.5937, 10.3852, -19.5937, 19.5937, 7.8747, -19.5937, 19.5937, 3.0597, -19.5937, 15.9358, -5.1583, -14.4746, -6.0605, -12.6408, -17.1911, -16.8871, -19.5250, -1.0101, -19.5839, -19.5937, 15.4259, -19.5839, -18.9661, 1.8829, -19.5839, -18.8974, -1.0885, -19.5839, -18.7307, 0.6963, -19.5839, -15.0630, 5.0210, -14.5923, -11.9249, 5.4721, -9.2477, -7.4432, 1.3827, 9.2477, -2.0202, -6.6391, 19.5937, 0.2648, -5.6584, 19.5937, -4.0011, 9.8459, 19.5937, -8.4828, 10.6892, 13.8470, -10.5716, 2.6772, -3.7069, -10.7677, -1.7260, -19.4368, -9.9341, -2.3536, -19.5839, -6.2861, -6.5705, -19.5839, -3.7559, -9.7968, -19.5839, -3.2852, -9.3555, -19.5839, -1.8437, -10.1891, -19.5839, -1.6671, -9.7478, -19.5839, 0.5590, -19.5937, -8.6004, 4.7366, -19.5937, 19.2799, 8.5906, -19.5839, 19.5937, 8.1101, -17.7893, 19.5937, 1.3337, -4.6974, 13.5528, -1.9613, -13.3567, 1.2356, -7.1687, -17.5637, -12.5427, -16.7203, -19.5643, -19.5839 };

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_test_1cpp_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {

    if (raw_features.size() != EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE) {
        printf("The size of your 'features' array is not correct. Expected %d items, but had %d\n",
               EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE, raw_features.size());
        return env->NewStringUTF("FAIL");
    }

    ei_impulse_result_t result;

    signal_t signal;
    numpy::signal_from_buffer(&raw_features[0], raw_features.size(), &signal);

    EI_IMPULSE_ERROR res = run_classifier(&signal, &result, false);
    std::string res_string = "";

    for (size_t ix = 0; ix < EI_CLASSIFIER_LABEL_COUNT; ix++) {
        res_string.append(result.classification[ix].label);
        res_string.append(" ");
        res_string.append(std::to_string(result.classification[ix].value));
        res_string.append("\n");
    }

    return env->NewStringUTF(res_string.c_str());
}
