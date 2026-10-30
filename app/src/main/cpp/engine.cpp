#include <jni.h>
#include <node.h>
#include <unistd.h>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

static std::string text(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_phonebot_android_engine_NodeRuntime_start(
    JNIEnv* env, jobject, jstring script, jstring home, jstring temp, jint input, jint output) {
    const auto homePath = text(env, home);
    const auto tempPath = text(env, temp);
    if (setenv("HOME", homePath.c_str(), 1) != 0 ||
        setenv("TMPDIR", tempPath.c_str(), 1) != 0 || chdir(homePath.c_str()) != 0) return -1;
    const std::vector<std::string> args = {
        "node", "--max-old-space-size=256", text(env, script),
        std::to_string(input), std::to_string(output)
    };
    // libuv can rewrite argv, so each argument must share one writable allocation.
    size_t size = 0;
    for (const auto& arg : args) size += arg.size() + 1;
    std::vector<char> storage(size);
    std::vector<char*> argv;
    char* next = storage.data();
    for (const auto& arg : args) {
        argv.push_back(next);
        std::memcpy(next, arg.c_str(), arg.size() + 1);
        next += arg.size() + 1;
    }
    argv.push_back(nullptr);
    const int result = node::Start(static_cast<int>(args.size()), argv.data());
    close(input);
    close(output);
    return result;
}
