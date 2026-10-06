#include <cstdlib>
#include <dlfcn.h>
#include <string>

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace {

__attribute__((constructor)) void initialize_qnn_runtime() {
    Dl_info library_info{};
    if (dladdr(reinterpret_cast<const void *>(&initialize_qnn_runtime), &library_info) == 0 ||
        library_info.dli_fname == nullptr) {
        return;
    }

    const std::string library_path = library_info.dli_fname;
    const auto separator = library_path.find_last_of('/');
    if (separator == std::string::npos) {
        return;
    }
    const std::string library_directory = library_path.substr(0, separator);
    std::string adsp_path = library_directory +
        ";/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/dsp";
    const char *previous_adsp = std::getenv("ADSP_LIBRARY_PATH");
    if (previous_adsp != nullptr && *previous_adsp != '\0') {
        adsp_path += ";" + std::string(previous_adsp);
    }
    setenv("ADSP_LIBRARY_PATH", adsp_path.c_str(), 1);

    std::string linker_path = library_directory;
    const char *previous_linker = std::getenv("LD_LIBRARY_PATH");
    if (previous_linker != nullptr && *previous_linker != '\0') {
        linker_path += ":" + std::string(previous_linker);
    }
    setenv("LD_LIBRARY_PATH", linker_path.c_str(), 1);

#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, "EdgeImpulseQNN", "Runtime path: %s",
                       library_directory.c_str());
#endif
}

}