#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_traductorandroid_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Traductor Android funcionando desde C++";
    return env->NewStringUTF(hello.c_str());
}