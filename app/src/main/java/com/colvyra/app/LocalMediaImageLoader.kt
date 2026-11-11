package com.colvyra.app

import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * One process-wide loader for local SAF images and video frame thumbnails.
 * Switching tabs must not discard the image cache or recreate decoders.
 *
 * The bounded disk cache lives inside the app's private cache directory.
 * It contains derivatives only, never the original media.
 */
internal object LocalMediaImageLoader {
    @Volatile private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        return instance ?: synchronized(this) {
            instance ?: ImageLoader.Builder(context.applicationContext)
                .components {
                    add(VideoFrameDecoder.Factory())
                    if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory())
                    else add(GifDecoder.Factory())
                }
                .memoryCache {
                    MemoryCache.Builder(context.applicationContext)
                        .maxSizePercent(0.15)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(context.applicationContext.cacheDir.resolve("local-media-thumbnails"))
                        .maxSizeBytes(128L * 1024 * 1024)
                        .build()
                }
                .build()
                .also { instance = it }
        }
    }
}
