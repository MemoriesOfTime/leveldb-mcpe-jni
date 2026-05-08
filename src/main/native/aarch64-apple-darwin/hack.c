//this symbol shouldn't even exist (it's the vtable for leveldb::ZlibCompressorBase, which is never instantiated), but there you have it
//this is needed by the Darwin linker, which does not allow the unresolved RTTI symbol in the JNI library
int _ZTIN7leveldb18ZlibCompressorBaseE = 0;
