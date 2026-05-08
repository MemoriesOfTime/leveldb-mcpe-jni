export SHELL		:=	/bin/bash

NPROC_CMD			:=	$(shell command -v nproc 2>/dev/null)
ifndef NPROC_CMD
export NPROC		:=	$(shell sysctl -n hw.ncpu 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 1)
else
export NPROC		:=	$(shell nproc)
endif

export TARGET		:=	$(shell basename $(CURDIR))
export TOPDIR		:=	$(CURDIR)
export TOOLCHAINS	:=	$(CURDIR)/toolchain
export COMMONSRC	:=	$(CURDIR)/src/main/native
UNAME_S				:=	$(shell uname -s 2>/dev/null || echo unknown)

#export CFLAGS		:=	-shared -Ofast -ffast-math -fPIC -ffunction-sections -fdata-sections
export CFLAGS		:=	-Ofast -ffast-math -fPIC -finput-charset=UTF-8 -fexec-charset=UTF-8
export CXXFLAGS		:=	$(CFLAGS)
#export LDFLAGS		:=	$(CFLAGS) -Wl,--gc-sections
export LDFLAGS		:=	$(CFLAGS) -shared

ifndef LDB_NATIVES_DEBUG
export CFLAGS		:=	$(CFLAGS)
export BUILD_TYPE	:=	release
else
export CFLAGS		:=	$(CFLAGS) -DLDB_NATIVES_DEBUG
export BUILD_TYPE	:=	debug
endif
$(info natives: building for $(BUILD_TYPE))

ifeq ($(UNAME_S),Darwin)
JNI_PLATFORM_INCLUDE	?=	darwin
else
JNI_PLATFORM_INCLUDE	?=	linux
endif

export INCLUDES		:=	$(JAVA_HOME)/include $(JAVA_HOME)/include/$(JNI_PLATFORM_INCLUDE)

export DARWIN_ARCHS	:=	x86_64-apple-darwin aarch64-apple-darwin
export DEFAULT_ARCHS	:=	x86_64-linux-gnu aarch64-linux-gnu x86_64-linux-musl aarch64-linux-musl arm-linux-gnueabihf x86_64-w64-mingw32 $(DARWIN_ARCHS)
ARCHS					?=	$(DEFAULT_ARCHS)
export ARCHS
export ARCH_TASKS	:=	$(foreach arch,$(ARCHS),build.$(arch))

export MODULES		:=	native

export LEVELDB_MCPE_VERSION		:=	1.22
export ZLIB_NG_VERSION			:=	2.3.3
export SNAPPY_VERSION			:=	1.2.2

export LEVELDB_MCPE_DIR			:=	leveldb-mcpe-$(LEVELDB_MCPE_VERSION)
export ZLIB_NG_DIR				:=	zlib-ng-$(ZLIB_NG_VERSION)
export SNAPPY_DIR				:=	snappy-$(SNAPPY_VERSION)

export LEVELDB_MCPE_ARCHIVE		:=	$(LEVELDB_MCPE_DIR).tar.gz
export ZLIB_NG_ARCHIVE			:=	$(ZLIB_NG_DIR).tar.gz
export SNAPPY_ARCHIVE			:=	$(SNAPPY_DIR).tar.gz

export LEVELDB_MCPE_URL			:=	https://cloud.daporkchop.net/programs/source/$(LEVELDB_MCPE_ARCHIVE)
export ZLIB_NG_URL				:=	https://github.com/zlib-ng/zlib-ng/archive/refs/tags/$(ZLIB_NG_VERSION).tar.gz
export SNAPPY_URL				:=	https://github.com/google/snappy/archive/refs/tags/$(SNAPPY_VERSION).tar.gz

export LIBS			:=	$(LEVELDB_MCPE_ARCHIVE) $(ZLIB_NG_ARCHIVE) $(SNAPPY_ARCHIVE)

.PHONY: build clean .FORCE

build: $(ARCH_TASKS)

ifneq ($(UNAME_S),Darwin)
.PHONY: $(addprefix build.,$(DARWIN_ARCHS))
$(addprefix build.,$(DARWIN_ARCHS)):
	@echo "Warning: skipping $(@:build.%=%) native build because Darwin targets can only be built on macOS (host: $(UNAME_S))."
endif

build.%: .FORCE $(foreach module,$(MODULES),%,$(module).lib)
	@echo Built libraries for $(shell echo '$@' | perl -n -e '/build\.(.+)/ && print $$1')!

%.lib: .FORCE $(LIBS)
	@_PRJ_NAME=$(shell echo "$@" | perl -n -e '/,(.*?)\.lib$$/ && print $$1') && \
		_ARCH=$(shell echo "$@" | perl -n -e '/^([^,]*?),.*?\.lib$$/ && print $$1') && \
		$(MAKE) --no-print-directory -C $(TOPDIR)/$$_PRJ_NAME BUILD=$$_ARCH PROJDIR=$(TOPDIR)/$$_PRJ_NAME $$_ARCH && \
		echo Built $$_PRJ_NAME for target $$_ARCH!

clean:
	@for f in $(MODULES); do $(MAKE) -C $(TOPDIR)/$$f clean; done

$(LEVELDB_MCPE_ARCHIVE):
	@echo "Downloading source for $@"
	@curl -fL -o $@ $(LEVELDB_MCPE_URL)

$(ZLIB_NG_ARCHIVE):
	@echo "Downloading source for $@"
	@curl -fL -o $@ $(ZLIB_NG_URL)

$(SNAPPY_ARCHIVE):
	@echo "Downloading source for $@"
	@curl -fL -o $@ $(SNAPPY_URL)

.FORCE:
