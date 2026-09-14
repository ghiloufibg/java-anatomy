// The JNI baseline the benchmark compares FFM against. Uses GetPrimitiveArrayCritical /
// ReleasePrimitiveArrayCritical to pin the caller's byte[] and hand the JVM a raw pointer into it
// directly - no copy - which is JNI's actual "critical" heap-array primitive (see this module's
// README for why java.lang.foreign has no equivalent method despite an earlier draft of this plan
// assuming one).
#include "dev_sevenrungs_nativeinterop_bench_JniZlib.h"
#include <zlib.h>
#include <stdlib.h>

JNIEXPORT jbyteArray JNICALL Java_dev_sevenrungs_nativeinterop_bench_JniZlib_compress(
    JNIEnv *env, jclass clazz, jbyteArray input, jint level) {
  jsize len = (*env)->GetArrayLength(env, input);

  jbyte *bytes = (*env)->GetPrimitiveArrayCritical(env, input, NULL);
  if (bytes == NULL) return NULL; // OOM pinning the array; let the caller see a null result

  uLong bound = compressBound((uLong)len);
  Bytef *dest = (Bytef *)malloc((size_t)bound);
  if (dest == NULL) {
    (*env)->ReleasePrimitiveArrayCritical(env, input, bytes, JNI_ABORT);
    return NULL;
  }

  uLongf destLen = (uLongf)bound;
  int rc = compress2(dest, &destLen, (const Bytef *)bytes, (uLong)len, (int)level);

  // No thread may block or call back into the JVM while an array is pinned critical, so release
  // it immediately after the native call, before touching the JVM again via NewByteArray.
  (*env)->ReleasePrimitiveArrayCritical(env, input, bytes, JNI_ABORT);

  if (rc != Z_OK) {
    free(dest);
    return NULL;
  }

  jbyteArray result = (*env)->NewByteArray(env, (jsize)destLen);
  if (result != NULL) {
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)destLen, (const jbyte *)dest);
  }
  free(dest);
  return result;
}

// The ordinary (non-critical) JNI path: GetByteArrayElements is free to copy the array instead of
// pinning it in place (the JVM decides; on HotSpot it typically does copy for the g1/generational
// collectors this project's other phase already covers). Kept only as the benchmark's second JNI
// data point, next to compress()'s critical/zero-copy path above.
JNIEXPORT jbyteArray JNICALL Java_dev_sevenrungs_nativeinterop_bench_JniZlib_compressCopying(
    JNIEnv *env, jclass clazz, jbyteArray input, jint level) {
  jsize len = (*env)->GetArrayLength(env, input);

  jbyte *bytes = (*env)->GetByteArrayElements(env, input, NULL);
  if (bytes == NULL) return NULL;

  uLong bound = compressBound((uLong)len);
  Bytef *dest = (Bytef *)malloc((size_t)bound);
  if (dest == NULL) {
    (*env)->ReleaseByteArrayElements(env, input, bytes, JNI_ABORT);
    return NULL;
  }

  uLongf destLen = (uLongf)bound;
  int rc = compress2(dest, &destLen, (const Bytef *)bytes, (uLong)len, (int)level);
  (*env)->ReleaseByteArrayElements(env, input, bytes, JNI_ABORT);

  if (rc != Z_OK) {
    free(dest);
    return NULL;
  }

  jbyteArray result = (*env)->NewByteArray(env, (jsize)destLen);
  if (result != NULL) {
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)destLen, (const jbyte *)dest);
  }
  free(dest);
  return result;
}
