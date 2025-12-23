package ai.flovyn.native

import java.io.File
import java.nio.file.Files

/**
 * Loads the native Flovyn FFI library for JNA.
 *
 * The native library is extracted from JAR resources and placed in a temp directory,
 * then the JNA library path is updated to include this directory so that JNA can find it.
 *
 * Supported platforms:
 * - Linux x86_64: libflovyn_ffi.so
 * - Linux aarch64: libflovyn_ffi.so
 * - macOS x86_64: libflovyn_ffi.dylib
 * - macOS aarch64: libflovyn_ffi.dylib
 * - Windows x86_64: flovyn_ffi.dll
 * - Windows aarch64: flovyn_ffi.dll
 */
object NativeLoader {
    private var loaded = false

    /**
     * Ensures the native library is available for JNA to load.
     * Must be called before any uniffi bindings are accessed.
     */
    @Synchronized
    fun ensureLoaded() {
        if (loaded) return

        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()

        // Handle different arch naming conventions
        // macOS on Apple Silicon can report as "aarch64" or "arm64"
        val isArm64 = arch.contains("aarch64") || arch.contains("arm64")
        val isAmd64 = arch.contains("amd64") || arch.contains("x86_64")

        val (platform, libName) = when {
            os.contains("mac") && isArm64 -> "macos-aarch64" to "libflovyn_ffi.dylib"
            os.contains("mac") && isAmd64 -> "macos-x86_64" to "libflovyn_ffi.dylib"
            os.contains("mac") -> "macos-aarch64" to "libflovyn_ffi.dylib" // Default to arm64 on macOS
            os.contains("linux") && isAmd64 -> "linux-x86_64" to "libflovyn_ffi.so"
            os.contains("linux") && isArm64 -> "linux-aarch64" to "libflovyn_ffi.so"
            os.contains("windows") && isArm64 -> "windows-aarch64" to "flovyn_ffi.dll"
            os.contains("windows") && isAmd64 -> "windows-x86_64" to "flovyn_ffi.dll"
            os.contains("windows") -> "windows-x86_64" to "flovyn_ffi.dll" // Default to x86_64 on Windows
            else -> error("Unsupported platform: $os $arch")
        }

        // Check if library override is set (allows using a custom library location)
        val libraryOverride = System.getProperty("uniffi.component.flovyn_ffi.libraryOverride")
        if (libraryOverride != null) {
            loaded = true
            return
        }

        // Try to find the library in existing JNA library path
        val existingJnaPath = System.getProperty("jna.library.path")
        if (existingJnaPath != null) {
            val existingFile = File(existingJnaPath, libName)
            if (existingFile.exists()) {
                loaded = true
                return
            }
        }

        // Extract bundled library from resources
        val resourcePath = "/natives/$platform/$libName"
        val resource = NativeLoader::class.java.getResourceAsStream(resourcePath)
            ?: error("Native library not found: $resourcePath. " +
                "Please ensure flovyn_ffi is bundled in the JAR for platform: $platform")

        // Create a temp directory for the extracted library
        val tempDir = Files.createTempDirectory("flovyn_ffi").toFile()
        tempDir.deleteOnExit()

        val tempLibFile = File(tempDir, libName)
        tempLibFile.deleteOnExit()

        resource.use { input ->
            tempLibFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        // Set JNA library path so it can find our extracted library
        val currentJnaPath = System.getProperty("jna.library.path", "")
        val newJnaPath = if (currentJnaPath.isEmpty()) {
            tempDir.absolutePath
        } else {
            "${tempDir.absolutePath}${File.pathSeparator}$currentJnaPath"
        }
        System.setProperty("jna.library.path", newJnaPath)

        loaded = true
    }
}
