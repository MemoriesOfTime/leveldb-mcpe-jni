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

import lombok.experimental.UtilityClass;

import java.lang.reflect.Constructor;

/**
 * @author DaPorkchop_
 */
@UtilityClass
public class LevelDB {
    /**
     * The currently used {@link DBProvider}.
     * <p>
     * This will always be an instance of {@link net.daporkchop.ldbjni.natives.NativeDBProvider} if possible, and will fall back to
     * {@link net.daporkchop.ldbjni.java.JavaDBProvider} otherwise.
     */
    public final DBProvider PROVIDER = loadProvider();

    private DBProvider loadProvider() {
        try {
            NativeLibraryLoader.loadNativeLibrary("", "net.daporkchop.ldbjni.natives.NativeDBProvider", LevelDB.class.getClassLoader());
            return instantiate("net.daporkchop.ldbjni.natives.NativeDBProvider");
        } catch (Throwable t) {
            if (Boolean.parseBoolean(System.getProperty("porklib.native.printStackTraces", "false"))) {
                t.printStackTrace();
            }
        }
        return instantiate("net.daporkchop.ldbjni.java.JavaDBProvider");
    }

    private DBProvider instantiate(String className) {
        try {
            Class<?> clazz = Class.forName(className, false, LevelDB.class.getClassLoader());
            Constructor<?> constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            return (DBProvider) constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to create DB provider: " + className, e);
        }
    }
}
