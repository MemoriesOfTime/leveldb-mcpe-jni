ifneq ($(BUILD),$(notdir $(CURDIR)))
.PHONY: $(BUILD) clean

export OUTDIR	:=	$(CURDIR)/../src/main/resources/net/daporkchop/ldbjni/natives
BUILDDIR		:=	../build/native/$(BUILD_TYPE)/$(BUILD)

clean:
	@if [ -d ../build/native/ ]; then rm -rf ../build/native/; fi
	@for arch in $(ARCHS); do rm -rf $(OUTDIR)/$$arch $(OUTDIR)/$$arch.so $(OUTDIR)/$$arch.dll $(OUTDIR)/$$arch.dylib; done

$(BUILD):
	@[ -d $(BUILDDIR) ] || mkdir -p $(BUILDDIR)
	@$(MAKE) --no-print-directory -C $(BUILDDIR) -f $(CURDIR)/Makefile BUILD=$(BUILD) build

else
.PHONY: build check-toolchain

include $(TOOLCHAINS)/$(BUILD)

SOURCES			:=	$(PROJDIR)/../src/main/native/common $(PROJDIR)/../src/main/native/$(BUILD)
SOURCES			:=	$(abspath $(SOURCES))

CFILES			:=	$(foreach dir,$(SOURCES),$(wildcard $(dir)/*.c))
CPPFILES		:=	$(foreach dir,$(SOURCES),$(wildcard $(dir)/*.cpp))
OFILES			:=	$(addsuffix .o,$(subst /,__,$(CFILES)) $(subst /,__,$(CPPFILES)))

INCLUDES		:=	$(INCLUDES) $(CURDIR)/lib-leveldb/include $(CURDIR)/lib-snappy
INCLUDE			:=	$(addprefix -I,$(INCLUDES) $(CURDIR))

build: libldb-jni.$(EXT) | check-toolchain
	@echo "Copying libldb-jni.$(EXT) to $(OUTDIR)/..."
	@[ -d $(OUTDIR) ] || mkdir -p $(OUTDIR)
	@cp libldb-jni.$(EXT) $(OUTDIR)/$(BUILD).$(EXT)

check-toolchain:
	@command -v "$(CC)" >/dev/null 2>&1 || { echo "Missing C compiler for $(BUILD): $(CC)"; exit 1; }
	@command -v "$(CXX)" >/dev/null 2>&1 || { echo "Missing C++ compiler for $(BUILD): $(CXX)"; exit 1; }
	@command -v "$(LD)" >/dev/null 2>&1 || { echo "Missing linker for $(BUILD): $(LD)"; exit 1; }
	@if [ -n "$(AR)" ]; then command -v "$(firstword $(AR))" >/dev/null 2>&1 || { echo "Missing archive tool for $(BUILD): $(AR)"; exit 1; }; fi
	@if [ -n "$(RANLIB)" ]; then command -v "$(firstword $(RANLIB))" >/dev/null 2>&1 || { echo "Missing archive index tool for $(BUILD): $(RANLIB)"; exit 1; }; fi
	@if [ -n "$(STRIP)" ]; then command -v "$(firstword $(STRIP))" >/dev/null 2>&1 || { echo "Missing strip tool for $(BUILD): $(STRIP)"; exit 1; }; fi

libldb-jni.$(EXT): $(CFILES) $(CPPFILES) $(OFILES) $(CURDIR)/lib-leveldb/libleveldb.a $(CURDIR)/lib-zlib/libz.a | check-toolchain
	@echo "Linking $@..."
	@$(LD) $(LDFLAGS) $(INCLUDE) -o $@ $(OFILES) $(CURDIR)/lib-leveldb/libleveldb.a $(CURDIR)/lib-zlib/libz.a $(CURDIR)/lib-snappy/libsnappy.a
	@echo "Stripping $@..."
	@[ -z "$(STRIP)" ] || $(STRIP) $@

%.c.o: $(CFILES) $(CURDIR)/lib-leveldb/libleveldb.a $(CURDIR)/lib-zlib/libz.a | check-toolchain
	@echo "Building $(subst .o,,$(subst __,/,$@))..."
	@$(CC) $(CFLAGS) $(INCLUDE) -c $(subst .o,,$(subst __,/,$@)) -o $@

%.cpp.o: $(CPPFILES) $(CURDIR)/lib-leveldb/libleveldb.a $(CURDIR)/lib-zlib/libz.a | check-toolchain
	@echo "Building $(subst .o,,$(subst __,/,$@))..."
	$(CXX) $(CXXFLAGS) $(INCLUDE) -c $(subst .o,,$(subst __,/,$@)) -o $@

$(CURDIR)/lib-leveldb/libleveldb.a: $(TOPDIR)/$(LEVELDB_MCPE_ARCHIVE) $(CURDIR)/lib-zlib/libz.a $(CURDIR)/lib-snappy/libsnappy.a $(TOOLCHAINS)/$(BUILD) $(TOOLCHAINS)/$(BUILD).cmake $(PROJDIR)/Makefile | check-toolchain
	@[ ! -d lib-leveldb/ ] || rm -rf lib-leveldb/
	@tar zxf $(TOPDIR)/$(LEVELDB_MCPE_ARCHIVE)
	@mv $(LEVELDB_MCPE_DIR)/ lib-leveldb/
	@[ -f "$(TOOLCHAINS)/$(BUILD).cmake" ]
	@cp $(TOPDIR)/leveldb-CMakeLists.txt lib-leveldb/CMakeLists.txt
	@cd lib-leveldb/ && cmake \
		-DCMAKE_TOOLCHAIN_FILE="$(TOOLCHAINS)/$(BUILD).cmake" \
		-DZLIB_LIBRARY="$(CURDIR)/lib-zlib/libz.a" -DZLIB_INCLUDE_DIR="$(CURDIR)/lib-zlib" \
		-DHAVE_SNAPPY=true -DSNAPPY_DIR="$(CURDIR)/lib-snappy" \
		-DLEVELDB_BUILD_TESTS=OFF -DLEVELDB_BUILD_BENCHMARKS=OFF -DLEVELDB_INSTALL=OFF .
	@$(MAKE) -C $(CURDIR)/lib-leveldb/ -f $(CURDIR)/lib-leveldb/Makefile

$(CURDIR)/lib-zlib/libz.a: $(TOPDIR)/$(ZLIB_NG_ARCHIVE) $(TOOLCHAINS)/$(BUILD) $(PROJDIR)/Makefile | check-toolchain
	@[ ! -d lib-zlib/ ] || rm -rf lib-zlib/
	@tar zxf $(TOPDIR)/$(ZLIB_NG_ARCHIVE)
	@mv $(ZLIB_NG_DIR)/ lib-zlib/
	@cd lib-zlib/ && CHOST="$(CHOST)" AR="$(AR)" ARFLAGS="$(ARFLAGS)" RANLIB="$(RANLIB)" ./configure --static --zlib-compat $(ZLIB_CONFIGURE_FLAGS) && cd ..
	@$(MAKE) -C $(CURDIR)/lib-zlib/ -f $(CURDIR)/lib-zlib/Makefile libz.a

$(CURDIR)/lib-snappy/libsnappy.a: $(TOPDIR)/$(SNAPPY_ARCHIVE) $(TOOLCHAINS)/$(BUILD) $(TOOLCHAINS)/$(BUILD).cmake $(PROJDIR)/Makefile | check-toolchain
	@[ ! -d lib-snappy/ ] || rm -rf lib-snappy/
	@tar zxf $(TOPDIR)/$(SNAPPY_ARCHIVE)
	@mv $(SNAPPY_DIR)/ lib-snappy/
	@[ -f "$(TOOLCHAINS)/$(BUILD).cmake" ]
	@cd lib-snappy/ && cmake \
		-DCMAKE_TOOLCHAIN_FILE="$(TOOLCHAINS)/$(BUILD).cmake" \
		-DSNAPPY_BUILD_TESTS=OFF -DSNAPPY_BUILD_BENCHMARKS=OFF -DSNAPPY_INSTALL=OFF .
	@$(MAKE) -C $(CURDIR)/lib-snappy/ -f $(CURDIR)/lib-snappy/Makefile

endif
