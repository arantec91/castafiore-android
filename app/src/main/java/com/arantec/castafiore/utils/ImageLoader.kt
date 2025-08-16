package com.arantec.castafiore.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.load.resource.bitmap.BitmapTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import com.arantec.castafiore.R

/**
 * Clase utilitaria para manejar la carga optimizada de imágenes con Glide
 */
object ImageLoader {

    /**
     * Opciones de caché optimizado para imágenes de música
     */
    private val musicImageOptions = RequestOptions()
        .diskCacheStrategy(DiskCacheStrategy.ALL) // Cachear tanto la imagen original como las transformadas
        .skipMemoryCache(false) // Usar caché de memoria
        .placeholder(R.drawable.ic_album_placeholder)
        .error(R.drawable.ic_album_placeholder)

    /**
     * Opciones para imágenes de álbum con transición suave
     */
    private val albumImageOptions = RequestOptions()
        .diskCacheStrategy(DiskCacheStrategy.ALL)
        .skipMemoryCache(false)
        .placeholder(R.drawable.ic_album_placeholder)
        .error(R.drawable.ic_album_placeholder)

    /**
     * Opciones para imágenes de artista con esquinas redondeadas
     */
    private val artistImageOptions = RequestOptions()
        .diskCacheStrategy(DiskCacheStrategy.ALL)
        .skipMemoryCache(false)
        .placeholder(R.drawable.ic_person)
        .error(R.drawable.ic_person)
        .circleCrop()

    /**
     * Opciones para thumbnails pequeños (listas)
     */
    private val thumbnailOptions = RequestOptions()
        .diskCacheStrategy(DiskCacheStrategy.ALL)
        .skipMemoryCache(false)
        .placeholder(R.drawable.ic_music_note)
        .error(R.drawable.ic_music_note)
        .override(200, 200) // Tamaño optimizado para thumbnails

    /**
     * Carga una imagen de álbum con transición suave
     */
    fun loadAlbumCover(
        context: Context,
        imageView: ImageView,
        url: String?,
        onSuccess: ((Bitmap) -> Unit)? = null,
        onError: (() -> Unit)? = null
    ) {
        if (url.isNullOrEmpty()) {
            imageView.setImageResource(R.drawable.ic_album_placeholder)
            onError?.invoke()
            return
        }

        Glide.with(context)
            .asBitmap()
            .load(url)
            .apply(albumImageOptions)
            .transition(BitmapTransitionOptions.withCrossFade(250))
            .into(imageView)
    }

    /**
     * Carga una imagen de álbum en un CustomTarget (para fragmentos)
     */
    fun loadAlbumCoverForFragment(
        fragment: Fragment,
        url: String?,
        onSuccess: (Bitmap) -> Unit,
        onError: () -> Unit = {}
    ) {
        if (url.isNullOrEmpty()) {
            onError()
            return
        }

        Glide.with(fragment)
            .asBitmap()
            .load(url)
            .apply(albumImageOptions)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    onSuccess(resource)
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    onError()
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    onError()
                }
            })
    }

    /**
     * Carga una imagen de artista circular
     */
    fun loadArtistImage(
        context: Context,
        imageView: ImageView,
        url: String?
    ) {
        Glide.with(context)
            .load(url)
            .apply(artistImageOptions)
            .transition(DrawableTransitionOptions.withCrossFade(250))
            .into(imageView)
    }

    /**
     * Carga una imagen de artista para fragmentos
     */
    fun loadArtistImageForFragment(
        fragment: Fragment,
        imageView: ImageView,
        url: String?
    ) {
        Glide.with(fragment)
            .load(url)
            .apply(artistImageOptions)
            .transition(DrawableTransitionOptions.withCrossFade(250))
            .into(imageView)
    }

    /**
     * Carga un thumbnail pequeño para listas
     */
    fun loadThumbnail(
        context: Context,
        imageView: ImageView,
        url: String?
    ) {
        Glide.with(imageView)
            .load(url)
            .apply(thumbnailOptions)
            .transition(DrawableTransitionOptions.withCrossFade(200))
            .into(imageView)
    }

    /**
     * Carga un thumbnail para fragmentos
     */
    fun loadThumbnailForFragment(
        fragment: Fragment,
        imageView: ImageView,
        url: String?
    ) {
        if (!fragment.isAdded) return // Verificación de seguridad

        Glide.with(fragment)
            .load(url)
            .apply(thumbnailOptions)
            .transition(DrawableTransitionOptions.withCrossFade(200))
            .into(imageView)
    }

    /**
     * Precarga una imagen para mejorar la experiencia
     */
    fun preloadImage(context: Context, url: String?) {
        if (url.isNullOrEmpty()) return

        Glide.with(context)
            .load(url)
            .apply(musicImageOptions)
            .preload()
    }

    /**
     * Limpia el caché de memoria cuando sea necesario
     */
    fun clearMemoryCache(context: Context) {
        Glide.get(context).clearMemory()
    }

    /**
     * Limpia el caché de disco (debe ejecutarse en background thread)
     */
    suspend fun clearDiskCache(context: Context) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Glide.get(context).clearDiskCache()
        }
    }

    /**
     * Configura Glide para usar un tamaño de caché de memoria más grande
     */
    fun configureGlideCache(context: Context) {
        // Esta configuración se debe hacer en el Application class o GlideModule
        val memoryCacheSize = 1024 * 1024 * 50 // 50MB
        val diskCacheSize = 1024 * 1024 * 250L // 250MB
    }

    /**
     * Construye URL de cover art con parámetros de autenticación
     */
    fun buildCoverArtUrl(
        serverUrl: String,
        albumId: String,
        username: String,
        token: String,
        salt: String,
        size: Int = 300
    ): String {
        return "$serverUrl/rest/getCoverArt.view?id=$albumId&u=$username&t=$token&s=$salt&v=1.16.1&c=Castafiore&size=$size"
    }

    /**
     * Construye URL de imagen de artista
     */
    fun buildArtistImageUrl(
        serverUrl: String,
        artistId: String,
        username: String,
        token: String,
        salt: String,
        size: Int = 300
    ): String {
        return "$serverUrl/rest/getCoverArt.view?id=$artistId&u=$username&t=$token&s=$salt&v=1.16.1&c=Castafiore&size=$size"
    }
}
