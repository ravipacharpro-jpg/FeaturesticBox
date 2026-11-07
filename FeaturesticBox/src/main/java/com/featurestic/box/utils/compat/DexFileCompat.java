package com.featurestic.box.utils.compat;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.DexFile;
import com.featurestic.box.utils.Reflector;

public class DexFileCompat {

    public static List<Long> getCookies(ClassLoader classLoader) {
        List<Long> cookies = new ArrayList<>();
        List<DexFile> dexFiles = getDexFiles(classLoader);
        for (DexFile dexFile : dexFiles) {
            cookies.addAll(getCookies(dexFile));
        }
        return cookies;
    }

    public static List<Long> getCookies(DexFile dexFile) {
        List<Long> cookies = new ArrayList<>();
        if (dexFile == null)
            return cookies;
        try {
            Object object = Reflector.with(dexFile)
                    .field("mCookie")
                    .get();
            if (BuildCompat.isM()) {
                for (long l : (long[]) object) {
                    cookies.add(l);
                }
            } else {
                cookies.add((long) object);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return cookies;
    }

    @SuppressWarnings("unchecked")
    public static void addNativeLibraryDirectories(ClassLoader classLoader, File... dirs) {
        if (classLoader == null || dirs == null || dirs.length == 0) return;
        Object dexPathList = getDexPathList(classLoader);
        if (dexPathList == null) return;
        List<File> addedDirs = new ArrayList<>();
        List<File> allNativeDirs = new ArrayList<>();
        try {
            Object nativeLibDirsObj = Reflector.with(dexPathList).field("nativeLibraryDirectories").get();
            if (nativeLibDirsObj instanceof List) {
                List<File> nativeLibDirs = (List<File>) nativeLibDirsObj;
                for (File dir : dirs) {
                    if (dir == null) continue;
                    if (!dir.exists()) dir.mkdirs();
                    if (!nativeLibDirs.contains(dir)) {
                        nativeLibDirs.add(0, dir);
                        addedDirs.add(dir);
                    }
                }
                allNativeDirs.addAll(nativeLibDirs);
            }
            Object sysDirsObj = Reflector.with(dexPathList).field("systemNativeLibraryDirectories").get();
            if (sysDirsObj instanceof List) {
                allNativeDirs.addAll((List<File>) sysDirsObj);
            }
        } catch (Throwable ignored) {
        }
        if (addedDirs.isEmpty()) return;

        // First try DexPathList.makePathElements(List<File>) on modern Android
        try {
            for (Method m : dexPathList.getClass().getDeclaredMethods()) {
                if ("makePathElements".equals(m.getName())) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length == 1 && List.class.isAssignableFrom(pts[0])) {
                        m.setAccessible(true);
                        Object rebuilt = m.invoke(null, allNativeDirs);
                        if (rebuilt != null) {
                            Reflector.with(dexPathList).field("nativeLibraryPathElements").set(rebuilt);
                            return;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // Fallback: construct NativeLibraryElement / Element instances directly
        try {
            Object existingElements = Reflector.with(dexPathList).field("nativeLibraryPathElements").get();
            if (existingElements != null && existingElements.getClass().isArray()) {
                Class<?> elemClass = existingElements.getClass().getComponentType();
                List<Object> created = new ArrayList<>();
                for (File d : addedDirs) {
                    Object elem = createNativeLibElement(elemClass, d);
                    if (elem != null) {
                        created.add(elem);
                    }
                }
                if (!created.isEmpty()) {
                    int oldLen = Array.getLength(existingElements);
                    Object newArray = Array.newInstance(elemClass, oldLen + created.size());
                    for (int i = 0; i < created.size(); i++) {
                        Array.set(newArray, i, created.get(i));
                    }
                    System.arraycopy(existingElements, 0, newArray, created.size(), oldLen);
                    Reflector.with(dexPathList).field("nativeLibraryPathElements").set(newArray);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static Object createNativeLibElement(Class<?> elemClass, File dir) {
        try {
            Constructor<?> ctor1 = elemClass.getDeclaredConstructor(File.class);
            ctor1.setAccessible(true);
            return ctor1.newInstance(dir);
        } catch (Throwable ignored) {
        }
        try {
            Constructor<?> ctor4 = elemClass.getDeclaredConstructor(
                    File.class, boolean.class, File.class, DexFile.class);
            ctor4.setAccessible(true);
            return ctor4.newInstance(dir, true, null, null);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static List<DexFile> getDexFiles(ClassLoader classLoader) {
        List<DexFile> dexFiles = new ArrayList<>();
        Object[] dexElements = getDexElements(classLoader);
        for (Object dexElement : dexElements) {
            try {
                dexFiles.add(Reflector.with(dexElement)
                        .field("dexFile")
                        .<DexFile>get());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return dexFiles;
    }

    private static Object[] getDexElements(ClassLoader classLoader) {
        Object dexPathList = getDexPathList(classLoader);
        if (dexPathList == null) {
            return new Object[]{};
        }
        try {
            return Reflector.with(dexPathList)
                    .field("dexElements")
                    .get();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new Object[]{};
    }

    private static Object getDexPathList(ClassLoader classLoader) {
        try {
            return Reflector.on("dalvik.system.BaseDexClassLoader")
                    .field("pathList")
                    .get(classLoader);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
