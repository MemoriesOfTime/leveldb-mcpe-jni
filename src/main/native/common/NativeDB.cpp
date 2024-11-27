#include "ldb-jni_common.h"

static jfieldID dbID;
static jfieldID dcaID;

static jmethodID get0_finalID;
static jmethodID getInto0_finalID;
static jmethodID getZeroCopy0_finalID;

extern "C" {

JNIEXPORT void JNICALL Java_net_daporkchop_ldbjni_natives_NativeDB_init
  (JNIEnv* env, jclass cla)  {
    dbID  = env->GetFieldID(cla, "db", "J");
    dcaID = env->GetFieldID(cla, "dca", "J");

    get0_finalID         = env->GetMethodID(cla, "get0_final", "([BLio/netty/buffer/ByteBufAllocator;Lnet/daporkchop/ldbjni/direct/BufType;)Lio/netty/buffer/ByteBuf;");
    getInto0_finalID     = env->GetMethodID(cla, "getInto0_final", "([BLio/netty/buffer/ByteBuf;)V");
    getZeroCopy0_finalID = env->GetMethodID(cla, "getZeroCopy0_final", "(JIJ)Lio/netty/buffer/ByteBuf;");
}

JNIEXPORT jlong JNICALL Java_net_daporkchop_ldbjni_natives_NativeDB_openDb
  (JNIEnv* env, jclass cla, jstring name,
   jboolean create_if_missing, jboolean error_if_exists, jboolean paranoid_checks, jint write_buffer_size,
   jint max_open_files, jint block_size, jint block_restart_interval, jint max_file_size, jint compression, jlong cacheSize)  {
    leveldb::DB* db;
    leveldb::Options options;

    loadOptions(env, options, create_if_missing, error_if_exists, paranoid_checks, write_buffer_size, max_open_files, block_size, block_restart_interval, max_file_size, compression, cacheSize);

    const char* name_native = env->GetStringUTFChars(name, nullptr);
    leveldb::Status status = leveldb::DB::Open(options, name_native, &db);
    env->ReleaseStringUTFChars(name, name_native);

    if (checkException(env, status)) {
        return (jlong) nullptr;
    }

    return (jlong) db;
}

JNIEXPORT jlong JNICALL Java_net_daporkchop_ldbjni_natives_NativeDB_createDecompressAllocator
  (JNIEnv* env, jclass cla)  {
    return (jlong) new leveldb::DecompressAllocator();
}

JNIEXPORT void JNICALL Java_net_daporkchop_ldbjni_natives_NativeDB_closeDb
  (JNIEnv* env, jclass cla, jlong db, jlong dca)  {
    delete (leveldb::DB*) db;
    delete (leveldb::DecompressAllocator*) dca;
}

JNIEXPORT void JNICALL Java_net_daporkchop_ldbjni_natives_NativeDB_deleteString
  (JNIEnv* env, jclass cla, jlong strAddr)  {
    delete (std::string*) strAddr;
}

JNIEXPORT jbyteArray JNICALL Java_net_daporkchop_ldbjni_natives_NativeDB_get0
  (JNIEnv* env, jobject obj, jbyteArray key, jboolean verifyChecksums, jboolean fillCache, jlong snapshot)  {
    auto db = (leveldb::DB*) env->GetLongField(obj, dbID);

    leveldb::ReadOptions readOptions;
    readOptions.verify_checksums = verifyChecksums;
    readOptions.fill_cache = fillCache;
    readOptions.snapshot = (leveldb::Snapshot*) snapshot;
    readOptions.decompress_allocator = (leveldb::DecompressAllocator*) env->GetLongField(obj, dcaID);

    int keyLength = env->GetArrayLength(key);
    auto keyPtr = (char*) env->GetPrimitiveArrayCritical(key, nullptr);
    if (!keyPtr)    {
        throwISE(env, "Unable to pin key array");
        return nullptr;
    }
    leveldb::Slice keySlice(keyPtr, keyLength);

    std::string value;
    leveldb::Status status = db->Get(readOptions, keySlice, &value);

    env->ReleasePrimitiveArrayCritical(key, keyPtr, 0);

    if (status.IsNotFound() || checkException(env, status))    {
        return (jbyteArray) nullptr;
    }

    jbyteArray out = env->NewByteArray(value.size());
    env->SetByteArrayRegion(out, 0, value.size(), (jbyte*) value.data());
    return out;
}

JNIEXPORT jlong JNICALL Java_net_daporkchop_ldbjni_natives_NativeDB_createWriteBatch0
  (JNIEnv* env, jobject obj)  {
    return (jlong) new leveldb::WriteBatch();
}

JNIEXPORT void JNICALL Java_net_daporkchop_ldbjni_natives_NativeDB_releaseWriteBatch0
  (JNIEnv* env, jobject obj, jlong writeBatch)  {
    if ((leveldb::WriteBatch*) writeBatch == nullptr)  {
        throwISE(env, "NativeWriteBatch has already been closed!");
        return;
    }

    delete (leveldb::WriteBatch*) writeBatch;
}

JNIEXPORT void JNICALL Java_net_daporkchop_ldbjni_natives_NativeDB_writeBatch0
  (JNIEnv* env, jobject obj, jlong writeBatch, jboolean sync)  {
    if ((leveldb::WriteBatch*) writeBatch == nullptr)  {
        throwISE(env, "NativeWriteBatch has already been closed!");
        return;
    }

    auto db = (leveldb::DB*) env->GetLongField(obj, dbID);

    leveldb::WriteOptions writeOptions;
    writeOptions.sync = sync;

    leveldb::Status status = db->Write(writeOptions, (leveldb::WriteBatch*) writeBatch);
    checkException(env, status);
}

JNIEXPORT void JNICALL Java_net_daporkchop_ldbjni_natives_NativeDB_compactRange0
  (JNIEnv* env, jobject obj, jbyteArray start, jbyteArray limit)  {
    auto db = (leveldb::DB*) env->GetLongField(obj, dbID);

    char* startRaw;
    leveldb::Slice startSlice;
    char* limitRaw;
    leveldb::Slice limitSlice;

    if (start != nullptr)   {
        int startLength = env->GetArrayLength(start);
        startRaw = new char[startLength];
        env->GetByteArrayRegion(start, 0, startLength, (jbyte*) startRaw);
        startSlice = leveldb::Slice(startRaw, startLength);
    }
    if (limit != nullptr)   {
        int limitLength = env->GetArrayLength(limit);
        limitRaw = new char[limitLength];
        env->GetByteArrayRegion(limit, 0, limitLength, (jbyte*) limitRaw);
        limitSlice = leveldb::Slice(limitRaw, limitLength);
    }

    db->CompactRange(start == nullptr ? nullptr : &startSlice, limit == nullptr ? nullptr : &limitSlice);

    if (start != nullptr)   {
        delete startRaw;
    }
    if (limit != nullptr)   {
        delete limitRaw;
    }
}

JNIEXPORT jobject JNICALL Java_net_daporkchop_ldbjni_natives_NativeDB_get0H
  (JNIEnv* env, jobject obj, jbyteArray key, jint keyOff, jint keyLen, jboolean verifyChecksums, jboolean fillCache, jlong snapshot, jobject alloc, jobject type)  {
    auto db = (leveldb::DB*) env->GetLongField(obj, dbID);

    leveldb::ReadOptions readOptions;
    readOptions.verify_checksums = verifyChecksums;
    readOptions.fill_cache = fillCache;
    readOptions.snapshot = (leveldb::Snapshot*) snapshot;
    readOptions.decompress_allocator = (leveldb::DecompressAllocator*) env->GetLongField(obj, dcaID);

    auto keyPtr = (char*) env->GetPrimitiveArrayCritical(key, nullptr);
    if (!keyPtr)    {
        throwISE(env, "Unable to pin key array");
        return nullptr;
    }
    leveldb::Slice keySlice(&keyPtr[keyOff], keyLen);

    std::string value;
    leveldb::Status status = db->Get(readOptions, keySlice, &value);

    env->ReleasePrimitiveArrayCritical(key, keyPtr, 0);

    if (status.IsNotFound() || checkException(env, status))    {
        return (jobject) nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(value.size()));
    env->SetByteArrayRegion(result, 0, value.size(), reinterpret_cast<const jbyte*>(value.c_str()));

    return env->CallObjectMethod(obj, get0_finalID, result, alloc, type);
}

JNIEXPORT jobject JNICALL Java_net_daporkchop_ldbjni_natives_NativeDB_get0D
  (JNIEnv* env, jobject obj, jlong keyAddr, jint keyLen, jboolean verifyChecksums, jboolean fillCache, jlong snapshot, jobject alloc, jobject type)  {
    auto db = (leveldb::DB*) env->GetLongField(obj, dbID);

    leveldb::ReadOptions readOptions;
    readOptions.verify_checksums = verifyChecksums;
    readOptions.fill_cache = fillCache;
    readOptions.snapshot = (leveldb::Snapshot*) snapshot;
    readOptions.decompress_allocator = (leveldb::DecompressAllocator*) env->GetLongField(obj, dcaID);

    leveldb::Slice keySlice((char*) keyAddr, keyLen);

    std::string value;
    leveldb::Status status = db->Get(readOptions, keySlice, &value);

    if (status.IsNotFound() || checkException(env, status))    {
        return (jobject) nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(value.size()));
    env->SetByteArrayRegion(result, 0, value.size(), reinterpret_cast<const jbyte*>(value.c_str()));

    return env->CallObjectMethod(obj, get0_finalID, result, alloc, type);
}

JNIEXPORT jboolean JNICALL Java_net_daporkchop_ldbjni_natives_NativeDB_getInto0H
  (JNIEnv* env, jobject obj, jbyteArray key, jint keyOff, jint keyLen, jboolean verifyChecksums, jboolean fillCache, jlong snapshot, jobject dst)  {
    auto db = (leveldb::DB*) env->GetLongField(obj, dbID);

    leveldb::ReadOptions readOptions;
    readOptions.verify_checksums = verifyChecksums;
    readOptions.fill_cache = fillCache;
    readOptions.snapshot = (leveldb::Snapshot*) snapshot;
    readOptions.decompress_allocator = (leveldb::DecompressAllocator*) env->GetLongField(obj, dcaID);

    auto keyPtr = (char*) env->GetPrimitiveArrayCritical(key, nullptr);
    if (!keyPtr)    {
        throwISE(env, "Unable to pin key array");
        return false;
    }
    leveldb::Slice keySlice(&keyPtr[keyOff], keyLen);

    std::string value;
    leveldb::Status status = db->Get(readOptions, keySlice, &value);

    env->ReleasePrimitiveArrayCritical(key, keyPtr, 0);

    if (status.IsNotFound() || checkException(env, status))    {
        return false;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(value.size()));
    env->SetByteArrayRegion(result, 0, value.size(), reinterpret_cast<const jbyte*>(value.c_str()));

    env->CallObjectMethod(obj, getInto0_finalID, result, dst);
    return true;
}

JNIEXPORT jboolean JNICALL Java_net_daporkchop_ldbjni_natives_NativeDB_getInto0D
  (JNIEnv* env, jobject obj, jlong keyAddr, jint keyLen, jboolean verifyChecksums, jboolean fillCache, jlong snapshot, jobject dst)  {
    auto db = (leveldb::DB*) env->GetLongField(obj, dbID);

    leveldb::ReadOptions readOptions;
    readOptions.verify_checksums = verifyChecksums;
    readOptions.fill_cache = fillCache;
    readOptions.snapshot = (leveldb::Snapshot*) snapshot;
    readOptions.decompress_allocator = (leveldb::DecompressAllocator*) env->GetLongField(obj, dcaID);

    leveldb::Slice keySlice((char*) keyAddr, keyLen);

    std::string value;
    leveldb::Status status = db->Get(readOptions, keySlice, &value);

    if (status.IsNotFound() || checkException(env, status))    {
        return false;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(value.size()));
    env->SetByteArrayRegion(result, 0, value.size(), reinterpret_cast<const jbyte*>(value.c_str()));

    env->CallObjectMethod(obj, getInto0_finalID, result, dst);
    return true;
}

JNIEXPORT jobject JNICALL Java_net_daporkchop_ldbjni_natives_NativeDB_getZeroCopy0H
  (JNIEnv* env, jobject obj, jbyteArray key, jint keyOff, jint keyLen, jboolean verifyChecksums, jboolean fillCache, jlong snapshot)  {
    auto db = (leveldb::DB*) env->GetLongField(obj, dbID);

    leveldb::ReadOptions readOptions;
    readOptions.verify_checksums = verifyChecksums;
    readOptions.fill_cache = fillCache;
    readOptions.snapshot = (leveldb::Snapshot*) snapshot;
    readOptions.decompress_allocator = (leveldb::DecompressAllocator*) env->GetLongField(obj, dcaID);

    auto keyPtr = (char*) env->GetPrimitiveArrayCritical(key, nullptr);
    if (!keyPtr)    {
        throwISE(env, "Unable to pin key array");
        return nullptr;
    }
    leveldb::Slice keySlice(&keyPtr[keyOff], keyLen);

    std::string* valuePtr = new std::string;
    leveldb::Status status = db->Get(readOptions, keySlice, valuePtr);

    env->ReleasePrimitiveArrayCritical(key, keyPtr, 0);

    if (status.IsNotFound() || checkException(env, status))    {
        delete valuePtr;
        return (jobject) nullptr;
    }

    return env->CallObjectMethod(obj, getZeroCopy0_finalID, (jlong) valuePtr->data(), valuePtr->size(), (jlong) valuePtr);
}

JNIEXPORT jobject JNICALL Java_net_daporkchop_ldbjni_natives_NativeDB_getZeroCopy0D
  (JNIEnv* env, jobject obj, jlong keyAddr, jint keyLen, jboolean verifyChecksums, jboolean fillCache, jlong snapshot)  {
    auto db = (leveldb::DB*) env->GetLongField(obj, dbID);

    leveldb::ReadOptions readOptions;
    readOptions.verify_checksums = verifyChecksums;
    readOptions.fill_cache = fillCache;
    readOptions.snapshot = (leveldb::Snapshot*) snapshot;
    readOptions.decompress_allocator = (leveldb::DecompressAllocator*) env->GetLongField(obj, dcaID);

    leveldb::Slice keySlice((char*) keyAddr, keyLen);

    std::string* valuePtr = new std::string;
    leveldb::Status status = db->Get(readOptions, keySlice, valuePtr);

    if (status.IsNotFound() || checkException(env, status))    {
        delete valuePtr;
        return (jobject) nullptr;
    }

    return env->CallObjectMethod(obj, getZeroCopy0_finalID, (jlong) valuePtr->data(), valuePtr->size(), (jlong) valuePtr);
}

JNIEXPORT void JNICALL Java_net_daporkchop_ldbjni_natives_NativeDB_put0HH
  (JNIEnv* env, jobject obj, jbyteArray key, jint keyOff, jint keyLen, jbyteArray value, jint valueOff, jint valueLen, jboolean sync)  {
    auto db = (leveldb::DB*) env->GetLongField(obj, dbID);

    leveldb::WriteOptions writeOptions;
    writeOptions.sync = sync;

    auto keyPtr = (char*) env->GetPrimitiveArrayCritical(key, nullptr);
    if (!keyPtr)    {
        throwException(env, "Unable to pin key array");
        return;
    }

    auto valuePtr = (char*) env->GetPrimitiveArrayCritical(value, nullptr);
    if (!valuePtr)    {
        env->ReleasePrimitiveArrayCritical(key, keyPtr, 0);
        throwException(env, "Unable to pin value array");
        return;
    }

    leveldb::Slice keySlice(&keyPtr[keyOff], keyLen);
    leveldb::Slice valueSlice(&valuePtr[valueOff], valueLen);

    leveldb::Status status = db->Put(writeOptions, keySlice, valueSlice);

    env->ReleasePrimitiveArrayCritical(value, valuePtr, 0);
    env->ReleasePrimitiveArrayCritical(key, keyPtr, 0);

    checkException(env, status);
}

JNIEXPORT void JNICALL Java_net_daporkchop_ldbjni_natives_NativeDB_put0HD
  (JNIEnv* env, jobject obj, jbyteArray key, jint keyOff, jint keyLen, jlong valueAddr, jint valueLen, jboolean sync)  {
    auto db = (leveldb::DB*) env->GetLongField(obj, dbID);

    leveldb::WriteOptions writeOptions;
    writeOptions.sync = sync;

    auto keyPtr = (char*) env->GetPrimitiveArrayCritical(key, nullptr);
    if (!keyPtr)    {
        throwException(env, "Unable to pin key array");
        return;
    }

    leveldb::Slice keySlice(&keyPtr[keyOff], keyLen);
    leveldb::Slice valueSlice((char*) valueAddr, valueLen);

    leveldb::Status status = db->Put(writeOptions, keySlice, valueSlice);

    env->ReleasePrimitiveArrayCritical(key, keyPtr, 0);

    checkException(env, status);
}

JNIEXPORT void JNICALL Java_net_daporkchop_ldbjni_natives_NativeDB_put0DH
  (JNIEnv* env, jobject obj, jlong keyAddr, jint keyLen, jbyteArray value, jint valueOff, jint valueLen, jboolean sync)  {
    auto db = (leveldb::DB*) env->GetLongField(obj, dbID);

    leveldb::WriteOptions writeOptions;
    writeOptions.sync = sync;

    auto valuePtr = (char*) env->GetPrimitiveArrayCritical(value, nullptr);
    if (!valuePtr)    {
        throwException(env, "Unable to pin value array");
        return;
    }

    leveldb::Slice keySlice((char*) keyAddr, keyLen);
    leveldb::Slice valueSlice(&valuePtr[valueOff], valueLen);

    leveldb::Status status = db->Put(writeOptions, keySlice, valueSlice);

    env->ReleasePrimitiveArrayCritical(value, valuePtr, 0);

    checkException(env, status);
}

JNIEXPORT void JNICALL Java_net_daporkchop_ldbjni_natives_NativeDB_put0DD
  (JNIEnv* env, jobject obj, jlong keyAddr, jint keyLen, jlong valueAddr, jint valueLen, jboolean sync)  {
    auto db = (leveldb::DB*) env->GetLongField(obj, dbID);

    leveldb::WriteOptions writeOptions;
    writeOptions.sync = sync;

    leveldb::Slice keySlice((char*) keyAddr, keyLen);
    leveldb::Slice valueSlice((char*) valueAddr, valueLen);

    leveldb::Status status = db->Put(writeOptions, keySlice, valueSlice);

    checkException(env, status);
}

JNIEXPORT void JNICALL Java_net_daporkchop_ldbjni_natives_NativeDB_delete0H
  (JNIEnv* env, jobject obj, jbyteArray key, jint keyOff, jint keyLen, jboolean sync)  {
    auto db = (leveldb::DB*) env->GetLongField(obj, dbID);

    leveldb::WriteOptions writeOptions;
    writeOptions.sync = sync;

    auto keyPtr = (char*) env->GetPrimitiveArrayCritical(key, nullptr);
    if (!keyPtr)    {
        throwException(env, "Unable to pin key array");
        return;
    }

    leveldb::Slice keySlice(&keyPtr[keyOff], keyLen);

    leveldb::Status status = db->Delete(writeOptions, keySlice);

    env->ReleasePrimitiveArrayCritical(key, keyPtr, 0);

    checkException(env, status);
}

JNIEXPORT void JNICALL Java_net_daporkchop_ldbjni_natives_NativeDB_delete0D
  (JNIEnv* env, jobject obj, jlong keyAddr, jint keyLen, jboolean sync)  {
    auto db = (leveldb::DB*) env->GetLongField(obj, dbID);

    leveldb::WriteOptions writeOptions;
    writeOptions.sync = sync;

    leveldb::Slice keySlice((char*) keyAddr, keyLen);

    leveldb::Status status = db->Delete(writeOptions, keySlice);

    checkException(env, status);
}

}
