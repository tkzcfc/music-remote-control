#include <jni.h>
#include <android/log.h>
#include <string>
#include "yasio_client.h"


#define LOG_TAG "NativeTcpClient"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

JavaVM* jvm = nullptr;

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
    jvm = vm;
    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_mrc_client_TcpClient_connect(JNIEnv *env, jobject thiz, jstring host, jint port,
                                      jint kind) {
    jclass cls = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(cls, "nativeHandle", "J");
    auto client = reinterpret_cast<yasio_client*>(env->GetLongField(thiz, fid));

    int result = -1;
    const char *nativeHost = env->GetStringUTFChars(host, 0);
    if(client) {
        result = client->connect(nativeHost, port, kind);
    }
    env->ReleaseStringUTFChars(host, nativeHost);

    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_mrc_client_TcpClient_disconnect(JNIEnv *env, jobject thiz, jint connection_id) {
    jclass cls = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(cls, "nativeHandle", "J");
    auto client = reinterpret_cast<yasio_client*>(env->GetLongField(thiz, fid));
    if(client) {
        client->disconnect(connection_id);
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_mrc_client_TcpClient_send(JNIEnv *env, jobject thiz, jint connection_id, jbyteArray data) {
    jclass cls = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(cls, "nativeHandle", "J");
    auto client = reinterpret_cast<yasio_client*>(env->GetLongField(thiz, fid));
    if(client) {
        // 获取 byte[] 数组的长度
        jsize length = env->GetArrayLength(data);

        // 获取 byte[] 数组的指针
        jbyte* byteArrayElements = env->GetByteArrayElements(data, NULL);
        if (byteArrayElements == NULL) {
            return -1002; // 处理错误情况
        }
        int result = client->send(connection_id, reinterpret_cast<const char*>(byteArrayElements), static_cast<size_t>(length));

        env->ReleaseByteArrayElements(data, byteArrayElements, 0);

        return result;
    }
    return -1003;
}

void call_on_message_received(JNIEnv* env, jobject obj, int event_type, int connection_id, const std::string_view& data) {
    // 获取Java类
    jclass cls = env->GetObjectClass(obj);
    if (cls == nullptr) {
        LOGE("Failed to find Java class");
        return;
    }

    // 获取Java方法ID
    jmethodID mid = env->GetMethodID(cls, "onMessageReceived", "(II[B)V");
    if (mid == nullptr) {
        LOGE("Failed to find method ID");
        return;
    }

    // 创建一个Java的byte数组
    jbyteArray jdata = env->NewByteArray(data.size());
    if (jdata == nullptr) {
        LOGE("Failed to create byte array");
        return;
    }

    // 填充byte数组
    env->SetByteArrayRegion(jdata, 0, data.size(), reinterpret_cast<const jbyte*>(data.data()));

    // 调用Java方法
    env->CallVoidMethod(obj, mid, event_type, connection_id, jdata);

    // 删除局部引用
    env->DeleteLocalRef(jdata);
    env->DeleteLocalRef(cls);
}

void start_client(yasio_client* client, jobject thiz, jint max_channel_count) {
    client->setEventCallback([=](int event_type, int connection_id, const std::string_view& data)-> void {
        // 获取JNI环境
        JNIEnv* env;
        jint res = jvm->AttachCurrentThread(&env, nullptr);
        if (res != JNI_OK) {
            return;
        }

        call_on_message_received(env, thiz, event_type, connection_id, data);

        jvm->DetachCurrentThread();
    });
    client->start(max_channel_count);

    // 获取JNI环境
    JNIEnv* env;
    jint res = jvm->AttachCurrentThread(&env, nullptr);
    if (res != JNI_OK) {
        return;
    }

    jclass cls = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(cls, "nativeHandle", "J");
    env->SetLongField(thiz, fid, jlong(0));
    env->DeleteLocalRef(cls);

    jvm->DetachCurrentThread();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_mrc_client_TcpClient_start(JNIEnv *env, jobject thiz, jint max_channel_count) {
    jclass cls = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(cls, "nativeHandle", "J");

    auto client = reinterpret_cast<yasio_client*>(env->GetLongField(thiz, fid));
    if(client)
        return;

    client = new yasio_client();
    env->SetLongField(thiz, fid, reinterpret_cast<jlong>(client));

    jobject globalObj = env->NewGlobalRef(thiz);
    std::thread([=](){
        start_client(client, globalObj, max_channel_count);
        env->DeleteGlobalRef(globalObj);
        delete client;
    }).detach();
}


extern "C"
JNIEXPORT void JNICALL
Java_com_mrc_client_TcpClient_stop(JNIEnv *env, jobject thiz) {
    jclass cls = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(cls, "nativeHandle", "J");
    auto client = reinterpret_cast<yasio_client*>(env->GetLongField(thiz, fid));
    if(client) {
        client->stop();
    }
}
