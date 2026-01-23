#include "log_mel_spectrogram.hpp"
#include <iostream>
#include <iomanip>
#define _USE_MATH_DEFINES
#include <cmath>
#include <vector>
#include <thread>
#include <algorithm>
#include <cassert>
#include <cstring>
#include <fstream>
#include "wavreader.h"

// Constants
#define WHISPER_N_FFT 400
#define WHISPER_HOP_LENGTH 160
#define WHISPER_SAMPLE_RATE 16000
#define SIN_COS_N_COUNT WHISPER_N_FFT
#define WHISPER_CHUNK_LENGTH 30
#define N_SAMPLES WHISPER_CHUNK_LENGTH * WHISPER_SAMPLE_RATE


namespace mel_spectrogram {

struct whisper_global_cache {
    float sin_vals[SIN_COS_N_COUNT];
    float cos_vals[SIN_COS_N_COUNT];
    float hann_window[WHISPER_N_FFT];

    whisper_global_cache() {
        fill_sin_cos_table();
        fill_hann_window(sizeof(hann_window)/sizeof(hann_window[0]), true, hann_window);
    }

    void fill_sin_cos_table() {
        for (int i = 0; i < SIN_COS_N_COUNT; i++) {
            double theta = (2 * M_PI * i) / SIN_COS_N_COUNT;
            sin_vals[i] = sinf(theta);
            cos_vals[i] = cosf(theta);
        }
    }

    void fill_hann_window(int length, bool periodic, float* output) {
        int offset = periodic ? 0 : -1;
        for (int i = 0; i < length; i++) {
            output[i] = 0.5 * (1.0 - cosf((2.0 * M_PI * i) / (length + offset)));
        }
    }
};

struct whisper_mel_data {
    int n_len;
    int n_len_org;
    int n_mel;
    std::vector<float> data;
};

struct whisper_filters {
    int n_fft;
    int n_mel;
    std::vector<float> data;
};

struct whisper_global_cache global_cache;

std::vector<float> pad_or_trim(const std::vector<float>& array, size_t length = N_SAMPLES) {
    std::vector<float> result;

    if (array.size() > length) {
        // Trim the array
        result.assign(array.begin(), array.begin() + length);
    } else if (array.size() < length) {
        // Pad the array
        result = array;
        result.resize(length, 0.0f);
    } else {
        // No need to pad or trim
        result = array;
    }

    return result;
}

void fft(const float* in, int N, float* out) {
    if (N == 1) {
        out[0] = in[0];
        out[1] = 0;
        return;
    }

    const int half_N = N / 2;
    if (N - half_N * 2 == 1) {
        // Use naive DFT if N is not a power of 2
        for (int k = 0; k < N; k++) {
            float re = 0;
            float im = 0;
            for (int n = 0; n < N; n++) {
                double theta = -2.0 * M_PI * k * n / N;
                re += in[n] * cos(theta);
                im += in[n] * sin(theta);
            }
            out[2 * k] = re;
            out[2 * k + 1] = im;
        }
        return;
    }

    std::vector<float> even;
    std::vector<float> odd;
	even.resize(N);
	odd.resize(N);
    for (int i = 0; i < half_N; ++i) {
        even[i] = in[2 * i];
        odd[i] = in[2 * i + 1];
    }

    std::vector<float> even_fft;
    std::vector<float> odd_fft;
	even_fft.resize(2 * N);
	odd_fft.resize(2 * N);
    fft(even.data(), half_N, even_fft.data());
    fft(odd.data(), half_N, odd_fft.data());

    for (int k = 0; k < half_N; ++k) {
        double theta = -2.0 * M_PI * k / N;
        float re = cos(theta);
        float im = sin(theta);
        float re_odd = odd_fft[2 * k];
        float im_odd = odd_fft[2 * k + 1];

        out[2 * k] = even_fft[2 * k] + re * re_odd - im * im_odd;
        out[2 * k + 1] = even_fft[2 * k + 1] + re * im_odd + im * re_odd;
        out[2 * (k + half_N)] = even_fft[2 * k] - re * re_odd + im * im_odd;
        out[2 * (k + half_N) + 1] = even_fft[2 * k + 1] - re * im_odd - im * re_odd;
    }
}

void log_mel_spectrogram_worker_thread(int ith, const float* hann, const std::vector<float>& samples, int n_samples, int n_threads, const whisper_filters& filters, whisper_mel_data& mel) {
    const auto frame_size = WHISPER_N_FFT;
    const auto frame_step = WHISPER_HOP_LENGTH;
    std::vector<float> fft_in(frame_size, 0.0f);
    std::vector<float> fft_out(frame_size * 2, 0.0f);
    int n_fft = filters.n_fft;
    int i = ith;

    assert(n_fft == 1 + (frame_size / 2));

    for (; i < std::min(n_samples / frame_step + 1, mel.n_len); i += n_threads) {
        const int offset = i * frame_step;

        for (int j = 0; j < std::min(frame_size, n_samples - offset); j++) {
            fft_in[j] = hann[j] * samples[offset + j];
        }

        if (n_samples - offset < frame_size) {
            std::fill(fft_in.begin() + (n_samples - offset), fft_in.end(), 0.0f);
        }

        fft(fft_in.data(), frame_size, fft_out.data());

        for (int j = 0; j < n_fft; j++) {
            fft_out[j] = (fft_out[2 * j] * fft_out[2 * j] + fft_out[2 * j + 1] * fft_out[2 * j + 1]);
        }

        for (int j = 0; j < mel.n_mel; j++) {
            double sum = 0.0;
            for (int k = 0; k < n_fft; k++) {
                sum += fft_out[k] * filters.data[j * n_fft + k];
            }
            sum = log10(std::max(sum, 1e-10));
            mel.data[j * mel.n_len + i] = static_cast<float>(sum);
        }
    }

    double sum = log10(1e-10);
    for (; i < mel.n_len; i += n_threads) {
        for (int j = 0; j < mel.n_mel; j++) {
            mel.data[j * mel.n_len + i] = static_cast<float>(sum);
        }
    }
}

 whisper_filters load_mel_filters(const char* filename) {

    whisper_filters filters;
    filters.n_fft = WHISPER_N_FFT / 2 + 1;
    filters.n_mel = 80;
    filters.data.resize(filters.n_mel * filters.n_fft, 0.1f); // Example filter values

    union {
      float f;
      uint8_t b[4];
    } u;

    uint8_t i[4] = { 0 };
    uint32_t d_i = 0;
    std::ifstream inFile;
    inFile.open(filename, std::ios::binary);
    if (!inFile.is_open()) {
        std::cerr << "Error opening file: " << filename << std::endl;
        // Check for specific error conditions
        if (inFile.bad()) {
            std::cerr << "Fatal error: badbit is set." << std::endl;
        }

        if (inFile.fail()) {
            // Print a more detailed error message using
            // strerror
            std::cerr << "Error details: " << strerror(errno) << std::endl;
        }

        // Handle the error or exit the program
        return filters;
    }

    while(inFile.read(reinterpret_cast<char*>(&i), sizeof(i))) {
      //std::cout << i;
      // Conversion 95 5d bb 3c => 0x3c bb 5d 95
      u.b[3] = i[3];
      u.b[2] = i[2];
      u.b[1] = i[1];
      u.b[0] = i[0];
      filters.data[d_i++] = u.f;
    }
    //std::cout << "D_I: " << d_i << std::endl;
    inFile.close();

    return filters;
}

class mel_calc_cpu {
public:
    mel_calc_cpu(std::string mel_filter_binfile) {
        //m_filters = load_mel_filters("mel_80.bin");
        //std::cout << "Filter: " << m_filters.data[1*201 + 2] << " \n";
        m_filters = load_mel_filters(mel_filter_binfile.c_str());
    }

    whisper_mel_data calculate(const std::vector<float>& samples, int n_threads) {
        const float* hann = global_cache.hann_window;

        int64_t stage_1_pad = WHISPER_SAMPLE_RATE * 30;
        int64_t stage_2_pad = WHISPER_N_FFT / 2;

        const int n_samples = static_cast<int>(samples.size());

        std::vector<float> samples_padded;
        samples_padded.resize(n_samples + stage_1_pad + stage_2_pad * 2);
        std::copy(samples.begin(), samples.end(), samples_padded.begin() + stage_2_pad);

        std::fill(samples_padded.begin() + n_samples + stage_2_pad, samples_padded.begin() + n_samples + stage_1_pad + 2 * stage_2_pad, 0);
        std::reverse_copy(samples.begin(), samples.begin() + stage_2_pad, samples_padded.begin());

        whisper_mel_data mel;
        mel.n_mel = m_filters.n_mel;
        mel.n_len_org = (samples_padded.size() - WHISPER_N_FFT) / WHISPER_HOP_LENGTH;
        mel.n_len = 2 + (n_samples + stage_2_pad - WHISPER_N_FFT) / WHISPER_HOP_LENGTH;

        mel.data.resize(mel.n_len * mel.n_mel);
        //mel.data = mel_data.data();

        std::vector<std::thread> workers(n_threads - 1);
        for (int iw = 0; iw < n_threads - 1; ++iw) {
            workers[iw] = std::thread(log_mel_spectrogram_worker_thread, iw + 1, hann, std::cref(samples_padded), n_samples + stage_2_pad, n_threads, std::cref(m_filters), std::ref(mel));
        }

        log_mel_spectrogram_worker_thread(0, hann, samples_padded, n_samples + stage_2_pad, n_threads, m_filters, mel);

        for (int iw = 0; iw < n_threads - 1; ++iw) {
            workers[iw].join();
        }

        double mmax = -1e20;
        for (int i = 0; i < mel.n_mel * mel.n_len; i++) {
            if (mel.data[i] > mmax) {
                mmax = mel.data[i];
            }
        }
        //std::cout << "Max: " << mmax << std::endl;
        mmax -= 8.0;
        for (int i = 0; i < mel.n_mel * mel.n_len; i++) {
            if (mel.data[i] < mmax) {
                mel.data[i] = static_cast<float>(mmax);
            }
            mel.data[i] = (mel.data[i] + 4.0f) / 4.0f;
        }

        return mel;
    }

private:
    whisper_filters m_filters;
};

LogMelSpectrogram::LogMelSpectrogram(std::string mel_filter_binfile) : n_threads(2)
{
    mel_calculator_sptr.reset(new mel_calc_cpu(mel_filter_binfile));
}

LogMelSpectrogram::~LogMelSpectrogram()
{

}

std::vector<float>
LogMelSpectrogram::compute(const std::vector<float>& audio_data)
{
    whisper_mel_data mel = mel_calculator_sptr->calculate(audio_data, n_threads);
    return mel.data;
}

std::vector<float>
LogMelSpectrogram::load_wav_audio_and_compute(const std::string& filename)
{
    std::vector<float> mel_spectrogram;
    void* h_x = wav_read_open(filename.c_str());
    if (h_x == NULL) 
    {
        std::cerr << "Error opening file: " << filename << std::endl;
        return mel_spectrogram;
    }

    int format, channels, sr, bits_per_sample;
    unsigned int data_length;
    int res = wav_get_header(h_x, &format, &channels, &sr, &bits_per_sample, &data_length);
    if (!res)
    {
        std::cerr << "get ref header error: " << res << std::endl;
        return mel_spectrogram;
    }

    int samples = data_length * 8 / bits_per_sample;
    std::vector<int16_t> tmp(samples);
    res = wav_read_data(h_x, reinterpret_cast<unsigned char*>(tmp.data()), data_length);
    if (res < 0)
    {
        std::cerr << "read wav file error: " << res << std::endl;
        return mel_spectrogram;
    }
    std::vector<float> x(samples);
    std::transform(tmp.begin(), tmp.end(), x.begin(),
        [](int16_t a) {
        return static_cast<float>(a) / 32767.f;
    });

    std::vector<float> paded_x = pad_or_trim(x, N_SAMPLES);
    mel_spectrogram = this->compute(paded_x);

    return mel_spectrogram;
}

std::vector<float> 
LogMelSpectrogram::load_wav_audio(const std::string& filename)
{
    std::vector<float> audio_chunks;
    void* h_x = wav_read_open(filename.c_str());
    if (h_x == NULL) 
    {
        std::cerr << "Error opening file: " << filename << std::endl;
        return audio_chunks;
    }

    int format, channels, sr, bits_per_sample;
    unsigned int data_length;
    int res = wav_get_header(h_x, &format, &channels, &sr, &bits_per_sample, &data_length);
    if (!res)
    {
        std::cerr << "get ref header error: " << res << std::endl;
        return audio_chunks;
    }

    int samples = data_length * 8 / bits_per_sample;
    std::vector<int16_t> tmp(samples);
    res = wav_read_data(h_x, reinterpret_cast<unsigned char*>(tmp.data()), data_length);
    if (res < 0)
    {
        std::cerr << "read wav file error: " << res << std::endl;
        return audio_chunks;
    }
    audio_chunks.resize(samples);
    std::transform(tmp.begin(), tmp.end(), audio_chunks.begin(),
        [](int16_t a) {
        return static_cast<float>(a) / 32767.f;
    });
    return audio_chunks;
}

std::vector<float>
LogMelSpectrogram::load_audio_chunk(const std::vector<float>& audio_samples)
{
    std::vector<float> mel_spectrogram;
    std::cout << "AudioSize: " << audio_samples.size() << std::endl;
    for (int i=0; i<10; i++)
    {
        std::cout << audio_samples[i] << std::endl;
    }

    std::vector<float> paded_x = pad_or_trim(audio_samples, N_SAMPLES);
    mel_spectrogram = this->compute(paded_x);

    return mel_spectrogram;
}

}