package net.daporkchop.ldbjni;

import org.junit.Test;

import java.io.FileNotFoundException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class NativeLibraryLoaderTest {
    private static final String LIBC_PROPERTY = "net.daporkchop.ldbjni.libc";
    private static final String NATIVE_PROVIDER_CLASS = "net.daporkchop.ldbjni.natives.NativeDBProvider";

    private static final PlatformCase[] SUPPORTED_PLATFORMS = {
            new PlatformCase("Linux", "amd64", "gnu", "x86_64-linux-gnu", "so"),
            new PlatformCase("GNU/Linux", "x86_64", "glibc", "x86_64-linux-gnu", "so"),
            new PlatformCase("Linux", "x86-64", "", "x86_64-linux-gnu", "so"),
            new PlatformCase("Linux", "aarch64", "gnu", "aarch64-linux-gnu", "so"),
            new PlatformCase("Linux", "arm64", "gnu", "aarch64-linux-gnu", "so"),
            new PlatformCase("Linux", "armv7l", "gnu", "arm-linux-gnueabihf", "so"),
            new PlatformCase("Linux", "arm", "gnu", "arm-linux-gnueabihf", "so"),
            new PlatformCase("Linux", "amd64", "musl", "x86_64-linux-musl", "so"),
            new PlatformCase("Linux", "x86_64", "MUSL libc", "x86_64-linux-musl", "so"),
            new PlatformCase("Linux", "aarch64", "musl", "aarch64-linux-musl", "so"),
            new PlatformCase("Linux", "arm64", "musl", "aarch64-linux-musl", "so"),
            new PlatformCase("Mac OS X", "x86_64", "", "x86_64-apple-darwin", "dylib"),
            new PlatformCase("macOS", "x86-64", "", "x86_64-apple-darwin", "dylib"),
            new PlatformCase("Mac OS X", "aarch64", "", "aarch64-apple-darwin", "dylib"),
            new PlatformCase("Darwin", "arm64", "", "aarch64-apple-darwin", "dylib"),
            new PlatformCase("Windows 11", "amd64", "", "x86_64-w64-mingw32", "dll"),
            new PlatformCase("Windows 10", "x86_64", "", "x86_64-w64-mingw32", "dll")
    };

    @Test
    public void testSupportedPlatformMappingsHavePackagedResources() throws ClassNotFoundException {
        Class<?> providerClass = Class.forName(NATIVE_PROVIDER_CLASS, false, NativeLibraryLoaderTest.class.getClassLoader());
        for (PlatformCase platform : SUPPORTED_PLATFORMS) {
            NativeLibraryLoader.PlatformLibrary actual = NativeLibraryLoader.detect(platform.osName, platform.osArch, platform.libcName);

            assertLibrary(platform.expectedArch, platform.expectedExtension, actual);
            if (requiresPackagedResource(platform.expectedArch)) {
                assertProviderResourceExists(providerClass, actual);
            }
        }
    }

    @Test
    public void testDarwinResourcesAreOnlyRequiredWhenHostBuildsDarwinTargets() {
        Set<String> defaultArchs = builtArchs("", "Linux");
        assertTrue(defaultArchs.contains("x86_64-linux-gnu"));
        assertFalse(defaultArchs.contains("x86_64-apple-darwin"));
        assertFalse(defaultArchs.contains("aarch64-apple-darwin"));

        Set<String> configuredArchs = builtArchs("x86_64-linux-gnu aarch64-apple-darwin", "Linux");
        assertTrue(configuredArchs.contains("x86_64-linux-gnu"));
        assertFalse(configuredArchs.contains("aarch64-apple-darwin"));

        Set<String> darwinArchs = builtArchs("", "Darwin");
        assertTrue(darwinArchs.contains("x86_64-apple-darwin"));
        assertTrue(darwinArchs.contains("aarch64-apple-darwin"));
    }

    @Test
    public void testConfiguredLibcOverridesLinuxDefaultDetection() {
        String previous = System.getProperty(LIBC_PROPERTY);

        try {
            System.setProperty(LIBC_PROPERTY, "musl");
            assertLibrary("x86_64-linux-musl", "so", NativeLibraryLoader.detect("Linux", "amd64"));

            System.setProperty(LIBC_PROPERTY, "gnu");
            assertLibrary("x86_64-linux-gnu", "so", NativeLibraryLoader.detect("Linux", "amd64"));
            assertEquals("gnu", NativeLibraryLoader.detectLibcForTest("Linux", "/lib/ld-musl-x86_64.so.1"));
            assertEquals("gnu", NativeLibraryLoader.detectLibcForTest("Linux", "", true));
        } finally {
            restoreProperty(LIBC_PROPERTY, previous);
        }
    }

    @Test
    public void testAutomaticLibcDetectionUsesProcessMappingsAndFileMarkers() {
        String previous = System.getProperty(LIBC_PROPERTY);

        try {
            System.clearProperty(LIBC_PROPERTY);

            assertEquals("gnu", NativeLibraryLoader.detectLibcForTest("Linux", ""));
            assertEquals("gnu", NativeLibraryLoader.detectLibcForTest("Linux", "/lib/ld-linux-x86-64.so.2"));
            assertEquals("musl", NativeLibraryLoader.detectLibcForTest("Linux", "/lib/ld-musl-x86_64.so.1"));
            assertEquals("musl", NativeLibraryLoader.detectLibcForTest("Linux", "/lib/libc.musl-x86_64.so.1"));
            assertEquals("musl", NativeLibraryLoader.detectLibcForTest("Linux", "", true));
            assertEquals("", NativeLibraryLoader.detectLibcForTest("Mac OS X", "/lib/ld-musl-x86_64.so.1"));
            assertEquals("", NativeLibraryLoader.detectLibcForTest("Mac OS X", "", true));
        } finally {
            restoreProperty(LIBC_PROPERTY, previous);
        }
    }

    @Test
    public void testMissingNativeResourceReportsResolvedPathAndClass() throws Throwable {
        String previousOsName = System.getProperty("os.name");
        String previousOsArch = System.getProperty("os.arch");
        String previousLibc = System.getProperty(LIBC_PROPERTY);

        try {
            System.setProperty("os.name", "Linux");
            System.setProperty("os.arch", "amd64");
            System.setProperty(LIBC_PROPERTY, "gnu");

            NativeLibraryLoader.loadNativeLibrary(
                    "/missing/native",
                    NativeLibraryLoaderTest.class.getName(),
                    NativeLibraryLoaderTest.class.getClassLoader());
            fail("Expected missing native resource to throw FileNotFoundException");
        } catch (FileNotFoundException e) {
            assertContains(e.getMessage(), "resource: x86_64-linux-gnu/missing/native.so");
            assertContains(e.getMessage(), "class: net.daporkchop.ldbjni.NativeLibraryLoaderTest");
        } finally {
            restoreProperty("os.name", previousOsName);
            restoreProperty("os.arch", previousOsArch);
            restoreProperty(LIBC_PROPERTY, previousLibc);
        }
    }

    @Test
    public void testUnsupportedPlatformMappingsFailWithInputs() {
        assertUnsupported("Solaris", "sparc", "gnu");
        assertUnsupported("Linux", "armv7l", "musl");
        assertUnsupported("Windows 11", "aarch64", "");
        assertUnsupported("Mac OS X", "ppc", "");
    }

    private static void assertLibrary(String arch, String extension, NativeLibraryLoader.PlatformLibrary actual) {
        assertEquals(arch, actual.arch);
        assertEquals(extension, actual.extension);
    }

    private static void assertProviderResourceExists(Class<?> providerClass, NativeLibraryLoader.PlatformLibrary library) {
        String resourcePath = library.arch + '.' + library.extension;
        URL resource = providerClass.getResource(resourcePath);
        assertNotNull("missing native resource: " + resourcePath, resource);
    }

    private static boolean requiresPackagedResource(String arch) {
        return builtArchs(System.getenv("ARCHS"), System.getProperty("os.name")).contains(arch);
    }

    private static Set<String> builtArchs(String archs, String osName) {
        Set<String> result = new HashSet<>();
        if (archs == null || archs.trim().isEmpty()) {
            result.addAll(Arrays.asList(
                    "x86_64-linux-gnu",
                    "aarch64-linux-gnu",
                    "x86_64-linux-musl",
                    "aarch64-linux-musl",
                    "arm-linux-gnueabihf",
                    "x86_64-w64-mingw32"));
            if (isDarwinHost(osName)) {
                result.add("x86_64-apple-darwin");
                result.add("aarch64-apple-darwin");
            }
            return result;
        }
        for (String arch : archs.trim().split("\\s+")) {
            if (!arch.endsWith("-apple-darwin") || isDarwinHost(osName)) {
                result.add(arch);
            }
        }
        return result;
    }

    private static boolean isDarwinHost(String osName) {
        String normalized = osName == null ? "" : osName.toLowerCase();
        return normalized.contains("mac") || normalized.contains("darwin");
    }

    private static void assertUnsupported(String osName, String osArch, String libcName) {
        try {
            NativeLibraryLoader.detect(osName, osArch, libcName);
            fail("Expected unsupported platform: os.name=" + osName + ", os.arch=" + osArch);
        } catch (IllegalStateException e) {
            assertContains(e.getMessage(), "os.name=" + osName);
            assertContains(e.getMessage(), "os.arch=" + osArch);
        }
    }

    private static void assertContains(String actual, String expected) {
        assertTrue("missing expected text: " + expected + " in: " + actual, actual.contains(expected));
    }

    private static void restoreProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }

    private static final class PlatformCase {
        final String osName;
        final String osArch;
        final String libcName;
        final String expectedArch;
        final String expectedExtension;

        PlatformCase(String osName, String osArch, String libcName, String expectedArch, String expectedExtension) {
            this.osName = osName;
            this.osArch = osArch;
            this.libcName = libcName;
            this.expectedArch = expectedArch;
            this.expectedExtension = expectedExtension;
        }
    }
}
