/* DO NOT EDIT THIS FILE - it is machine generated */
#include <jni.h>
/* Header for class org_apache_hadoop_io_nativeio_NativeIO_Windows */

#ifndef _Included_org_apache_hadoop_io_nativeio_NativeIO_Windows
#define _Included_org_apache_hadoop_io_nativeio_NativeIO_Windows
#ifdef __cplusplus
extern "C" {
#endif
#undef org_apache_hadoop_io_nativeio_NativeIO_Windows_GENERIC_READ
#define org_apache_hadoop_io_nativeio_NativeIO_Windows_GENERIC_READ 2147483648i64
#undef org_apache_hadoop_io_nativeio_NativeIO_Windows_GENERIC_WRITE
#define org_apache_hadoop_io_nativeio_NativeIO_Windows_GENERIC_WRITE 1073741824i64
#undef org_apache_hadoop_io_nativeio_NativeIO_Windows_FILE_SHARE_READ
#define org_apache_hadoop_io_nativeio_NativeIO_Windows_FILE_SHARE_READ 1i64
#undef org_apache_hadoop_io_nativeio_NativeIO_Windows_FILE_SHARE_WRITE
#define org_apache_hadoop_io_nativeio_NativeIO_Windows_FILE_SHARE_WRITE 2i64
#undef org_apache_hadoop_io_nativeio_NativeIO_Windows_FILE_SHARE_DELETE
#define org_apache_hadoop_io_nativeio_NativeIO_Windows_FILE_SHARE_DELETE 4i64
#undef org_apache_hadoop_io_nativeio_NativeIO_Windows_CREATE_NEW
#define org_apache_hadoop_io_nativeio_NativeIO_Windows_CREATE_NEW 1i64
#undef org_apache_hadoop_io_nativeio_NativeIO_Windows_CREATE_ALWAYS
#define org_apache_hadoop_io_nativeio_NativeIO_Windows_CREATE_ALWAYS 2i64
#undef org_apache_hadoop_io_nativeio_NativeIO_Windows_OPEN_EXISTING
#define org_apache_hadoop_io_nativeio_NativeIO_Windows_OPEN_EXISTING 3i64
#undef org_apache_hadoop_io_nativeio_NativeIO_Windows_OPEN_ALWAYS
#define org_apache_hadoop_io_nativeio_NativeIO_Windows_OPEN_ALWAYS 4i64
#undef org_apache_hadoop_io_nativeio_NativeIO_Windows_TRUNCATE_EXISTING
#define org_apache_hadoop_io_nativeio_NativeIO_Windows_TRUNCATE_EXISTING 5i64
#undef org_apache_hadoop_io_nativeio_NativeIO_Windows_FILE_BEGIN
#define org_apache_hadoop_io_nativeio_NativeIO_Windows_FILE_BEGIN 0i64
#undef org_apache_hadoop_io_nativeio_NativeIO_Windows_FILE_CURRENT
#define org_apache_hadoop_io_nativeio_NativeIO_Windows_FILE_CURRENT 1i64
#undef org_apache_hadoop_io_nativeio_NativeIO_Windows_FILE_END
#define org_apache_hadoop_io_nativeio_NativeIO_Windows_FILE_END 2i64
#undef org_apache_hadoop_io_nativeio_NativeIO_Windows_FILE_ATTRIBUTE_NORMAL
#define org_apache_hadoop_io_nativeio_NativeIO_Windows_FILE_ATTRIBUTE_NORMAL 128i64
/*
 * Class:     org_apache_hadoop_io_nativeio_NativeIO_Windows
 * Method:    createDirectoryWithMode0
 * Signature: (Ljava/lang/String;I)V
 */
JNIEXPORT void JNICALL Java_org_apache_hadoop_io_nativeio_NativeIO_00024Windows_createDirectoryWithMode0
  (JNIEnv *, jclass, jstring, jint);

/*
 * Class:     org_apache_hadoop_io_nativeio_NativeIO_Windows
 * Method:    createFile
 * Signature: (Ljava/lang/String;JJJ)Ljava/io/FileDescriptor;
 */
JNIEXPORT jobject JNICALL Java_org_apache_hadoop_io_nativeio_NativeIO_00024Windows_createFile
  (JNIEnv *, jclass, jstring, jlong, jlong, jlong);

/*
 * Class:     org_apache_hadoop_io_nativeio_NativeIO_Windows
 * Method:    createFileWithMode0
 * Signature: (Ljava/lang/String;JJJI)Ljava/io/FileDescriptor;
 */
JNIEXPORT jobject JNICALL Java_org_apache_hadoop_io_nativeio_NativeIO_00024Windows_createFileWithMode0
  (JNIEnv *, jclass, jstring, jlong, jlong, jlong, jint);

/*
 * Class:     org_apache_hadoop_io_nativeio_NativeIO_Windows
 * Method:    setFilePointer
 * Signature: (Ljava/io/FileDescriptor;JJ)J
 */
JNIEXPORT jlong JNICALL Java_org_apache_hadoop_io_nativeio_NativeIO_00024Windows_setFilePointer
  (JNIEnv *, jclass, jobject, jlong, jlong);

/*
 * Class:     org_apache_hadoop_io_nativeio_NativeIO_Windows
 * Method:    getOwner
 * Signature: (Ljava/io/FileDescriptor;)Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL Java_org_apache_hadoop_io_nativeio_NativeIO_00024Windows_getOwner
  (JNIEnv *, jclass, jobject);

/*
 * Class:     org_apache_hadoop_io_nativeio_NativeIO_Windows
 * Method:    access0
 * Signature: (Ljava/lang/String;I)Z
 */
JNIEXPORT jboolean JNICALL Java_org_apache_hadoop_io_nativeio_NativeIO_00024Windows_access0
  (JNIEnv *, jclass, jstring, jint);

/*
 * Class:     org_apache_hadoop_io_nativeio_NativeIO_Windows
 * Method:    extendWorkingSetSize
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_org_apache_hadoop_io_nativeio_NativeIO_00024Windows_extendWorkingSetSize
  (JNIEnv *, jclass, jlong);

#ifdef __cplusplus
}
#endif
#endif
