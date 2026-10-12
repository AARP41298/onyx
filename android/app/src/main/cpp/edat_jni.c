#include <jni.h>
#include <stdbool.h>
#include <stdlib.h>

bool pack_data_from_paths(
    const char *input_path,
    const char *output_path,
    const char *hash_file_name,
    unsigned char *content_id,
    unsigned char *devklic,
    unsigned char *rifkey,
    int version,
    int license,
    int type,
    int block,
    bool useCompression,
    bool isEDAT,
    bool isFinalized,
    bool verbose);

JNIEXPORT jboolean JNICALL
Java_org_onyxite_quickconvert_convert_EdatPacker_packNative(
    JNIEnv *env,
    jobject thiz,
    jstring input_path,
    jstring output_path,
    jstring hash_name,
    jbyteArray content_id,
    jbyteArray klic) {
    (void)thiz;
    const char *in_path = (*env)->GetStringUTFChars(env, input_path, NULL);
    const char *out_path = (*env)->GetStringUTFChars(env, output_path, NULL);
    const char *name = (*env)->GetStringUTFChars(env, hash_name, NULL);
    jbyte *cid = (*env)->GetByteArrayElements(env, content_id, NULL);
    jbyte *key = (*env)->GetByteArrayElements(env, klic, NULL);
    unsigned char rif[16] = {0};

    bool failed = pack_data_from_paths(
        in_path,
        out_path,
        name,
        (unsigned char *)cid,
        (unsigned char *)key,
        rif,
        2,
        3,
        0,
        16,
        false,
        true,
        true,
        false);

    (*env)->ReleaseByteArrayElements(env, content_id, cid, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, klic, key, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, input_path, in_path);
    (*env)->ReleaseStringUTFChars(env, output_path, out_path);
    (*env)->ReleaseStringUTFChars(env, hash_name, name);
    return failed ? JNI_FALSE : JNI_TRUE;
}
