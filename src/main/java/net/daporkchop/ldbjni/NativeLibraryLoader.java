/*
 * Adapted from The MIT License (MIT)
 *
 * Copyright (c) 2020-2020 DaPorkchop_
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software
 * is furnished to do so, subject to the following conditions:
 *
 * Any persons and/or organizations using this software must include the above copyright notice and this permission notice,
 * provide sufficient credit to the original authors of the project (IE: DaPorkchop_), as well as provide a link to the original project.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
 * BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 */

package net.daporkchop.ldbjni;

import lombok.NonNull;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

final class NativeLibraryLoader {
    private static final String LIBC_PROPERTY = "net.daporkchop.ldbjni.libc";

    private NativeLibraryLoader() {
        throw new UnsupportedOperationException();
    }

    static Class<?> loadNativeLibrary(@NonNull String libName, @NonNull String className, @NonNull ClassLoader classLoader) throws Throwable {
        PlatformLibrary library = detect(System.getProperty("os.name"), System.getProperty("os.arch"));
        Class<?> clazz = Class.forName(className, false, classLoader);
        String resourcePath = resourcePath(libName, library);
        Path tempFile = Files.createTempFile("ldbjni-" + library.arch + '-' + UUID.randomUUID(), '.' + library.extension);

        try (InputStream in = clazz.getResourceAsStream(resourcePath)) {
            if (in == null) {
                Files.deleteIfExists(tempFile);
                throw new FileNotFoundException("resource: " + resourcePath + ", class: " + clazz.getCanonicalName());
            }
            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }

        tempFile.toFile().deleteOnExit();
        System.load(tempFile.toAbsolutePath().toString());
        return clazz;
    }

    static PlatformLibrary detect(String osName, String osArch) {
        return detect(osName, osArch, detectLibc(osName, osArch));
    }

    static PlatformLibrary detect(String osName, String osArch, String libcName) {
        String os = normalize(osName);
        String arch = normalize(osArch);
        boolean musl = isMusl(libcName);

        if (os.contains("linux")) {
            if (isX8664(arch)) {
                return new PlatformLibrary(musl ? "x86_64-linux-musl" : "x86_64-linux-gnu", "so");
            } else if (isAarch64(arch)) {
                return new PlatformLibrary(musl ? "aarch64-linux-musl" : "aarch64-linux-gnu", "so");
            } else if (!musl && (arch.equals("arm") || arch.startsWith("armv7"))) {
                return new PlatformLibrary("arm-linux-gnueabihf", "so");
            }
        } else if (os.contains("mac") || os.contains("darwin")) {
            if (isX8664(arch)) {
                return new PlatformLibrary("x86_64-apple-darwin", "dylib");
            } else if (isAarch64(arch)) {
                return new PlatformLibrary("aarch64-apple-darwin", "dylib");
            }
        } else if (os.contains("windows")) {
            if (isX8664(arch)) {
                return new PlatformLibrary("x86_64-w64-mingw32", "dll");
            }
        }

        throw new IllegalStateException("Unsupported native platform: os.name=" + osName + ", os.arch=" + osArch);
    }

    private static String detectLibc(String osName, String osArch) {
        return detectLibc0(osName, processMaps(), hasMuslFileMarker(osArch));
    }

    static String detectLibcForTest(String osName, String mappedLibraries) {
        return detectLibc0(osName, mappedLibraries, false);
    }

    static String detectLibcForTest(String osName, String mappedLibraries, boolean muslFileMarker) {
        return detectLibc0(osName, mappedLibraries, muslFileMarker);
    }

    private static String detectLibc0(String osName, String mappedLibraries, boolean muslFileMarker) {
        if (!normalize(osName).contains("linux")) {
            return "";
        }

        String configured = systemProperty(LIBC_PROPERTY);
        if (!configured.isEmpty()) {
            return configured;
        } else if (hasMuslProcessMapping(mappedLibraries) || muslFileMarker) {
            return "musl";
        } else {
            return "gnu";
        }
    }

    private static String systemProperty(String key) {
        try {
            return System.getProperty(key, "");
        } catch (SecurityException e) {
            return "";
        }
    }

    private static String processMaps() {
        try {
            Path p = Path.of("/proc/self/maps");
            return Files.isReadable(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
        } catch (IOException | SecurityException e) {
            return "";
        }
    }

    private static boolean hasMuslProcessMapping(String mappedLibraries) {
        return mappedLibraries != null && (mappedLibraries.contains("ld-musl") || mappedLibraries.contains("libc.musl"));
    }

    private static boolean hasMuslFileMarker(String osArch) {
        return exists("/etc/alpine-release") || exists(muslLoaderPath(osArch));
    }

    private static boolean exists(String path) {
        if (path.isEmpty()) {
            return false;
        }
        try {
            return Files.exists(Path.of(path));
        } catch (SecurityException e) {
            return false;
        }
    }

    private static String muslLoaderPath(String osArch) {
        String arch = normalize(osArch);
        if (isX8664(arch)) {
            return "/lib/ld-musl-x86_64.so.1";
        } else if (isAarch64(arch)) {
            return "/lib/ld-musl-aarch64.so.1";
        } else {
            return "";
        }
    }

    private static boolean isMusl(String libcName) {
        return normalize(libcName).contains("musl");
    }

    private static String resourcePath(String libName, PlatformLibrary library) {
        if (libName.startsWith("/")) {
            libName = libName.substring(1);
        }
        return libName.isEmpty()
                ? library.arch + '.' + library.extension
                : library.arch + '/' + libName + '.' + library.extension;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static boolean isX8664(String arch) {
        return arch.equals("x86_64") || arch.equals("amd64");
    }

    private static boolean isAarch64(String arch) {
        return arch.equals("aarch64") || arch.equals("arm64");
    }

    static final class PlatformLibrary {
        final String arch;
        final String extension;

        PlatformLibrary(String arch, String extension) {
            this.arch = arch;
            this.extension = extension;
        }
    }
}
