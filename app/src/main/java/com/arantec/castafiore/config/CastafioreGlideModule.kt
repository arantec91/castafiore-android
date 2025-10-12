package com.arantec.castafiore.config

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.transition.DrawableCrossFadeFactory
import com.bumptech.glide.load.resource.bitmap.BitmapTransitionOptions
import okhttp3.OkHttpClient
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Configuración personalizada de Glide para optimizar el rendimiento de carga de imágenes
 */
@GlideModule
class CastafioreGlideModule : AppGlideModule() {

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        // Configurar OkHttp con timeouts optimizados para imágenes de música
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("User-Agent", "Castafiore/1.0")
                    .addHeader("Accept", "image/*")
                    .build()
                chain.proceed(request)
            }
            .build()

        // Reemplazar el loader por defecto con nuestro OkHttpClient optimizado
        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            OkHttpUrlLoader.Factory(okHttpClient)
        )
    }

    override fun applyOptions(context: Context, builder: com.bumptech.glide.GlideBuilder) {
        super.applyOptions(context, builder)

        // Configurar caché de memoria más grande (80MB para más imágenes en memoria)
        val memorySizeBytes = 1024 * 1024 * 80 // 80MB
        builder.setMemoryCache(com.bumptech.glide.load.engine.cache.LruResourceCache(memorySizeBytes.toLong()))

        // Configurar caché de disco más grande (500MB)
        val diskCacheSizeBytes = 1024 * 1024 * 500L // 500MB
        builder.setDiskCache(
            com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory(
                context,
                "image_cache",
                diskCacheSizeBytes
            )
        )

        // Configurar nivel de log - usar WARN para reducir logs en todas las versiones
        builder.setLogLevel(android.util.Log.WARN)

        // CRÍTICO: Configurar transiciones que NO se ejecutan desde MEMORIA caché
        // pero SÍ desde red o disco caché (para feedback visual)
        val crossFadeFactory = DrawableCrossFadeFactory.Builder(200)
            .setCrossFadeEnabled(false) // Desactivar crossfade desde memoria caché
            .build()

        builder.setDefaultTransitionOptions(
            Drawable::class.java,
            DrawableTransitionOptions.withCrossFade(crossFadeFactory)
        )

        // Para Bitmaps, también desactivar crossfade desde memoria
        builder.setDefaultTransitionOptions(
            Bitmap::class.java,
            BitmapTransitionOptions.withCrossFade(200)
        )

        // Configurar el pool de Bitmaps para reutilización eficiente
        builder.setBitmapPool(com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool(memorySizeBytes.toLong()))

        // Configurar el ArrayPool para mejor manejo de memoria
        builder.setArrayPool(com.bumptech.glide.load.engine.bitmap_recycle.LruArrayPool(memorySizeBytes / 2))
    }

    // Deshabilitar manifests parsing para mejor rendimiento
    override fun isManifestParsingEnabled(): Boolean = false
}
