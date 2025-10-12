package com.arantec.castafiore.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.BitmapTransitionOptions
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import com.arantec.castafiore.R
import java.io.File

/**
 * Clase utilitaria para manejar la carga optimizada de imágenes con Glide
 */
object ImageLoader {

    /**
     * Opciones de caché optimizado CON placeholder
     * El placeholder se muestra solo cuando se carga desde red/disco
     */
    private val musicImageOptions = RequestOptions()
        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
        .skipMemoryCache(false)
        .placeholder(R.drawable.ic_album_placeholder)
        .error(R.drawable.ic_album_placeholder)
        .dontAnimate()

    /**
     * Opciones para imágenes de álbum CON placeholder
     */
    private val albumImageOptions = RequestOptions()
        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
        .skipMemoryCache(false)
        .placeholder(R.drawable.ic_album_placeholder)
        .error(R.drawable.ic_album_placeholder)
        .dontAnimate()

    /**
     * Opciones para imágenes de artista CON placeholder
     */
    private val artistImageOptions = RequestOptions()
        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
        .skipMemoryCache(false)
        .placeholder(R.drawable.ic_person)
        .error(R.drawable.ic_person)
        .circleCrop()
        .dontAnimate()

    /**
     * Opciones para thumbnails CON placeholder
     */
    private val thumbnailOptions = RequestOptions()
        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
        .skipMemoryCache(false)
        .placeholder(R.drawable.ic_music_note)
        .error(R.drawable.ic_music_note)
        .override(200, 200)
        .dontAnimate()

    /**
     * Carga una imagen local (File path) con opciones de thumbnail
     */
    fun loadLocalThumbnail(context: Context, imageView: ImageView, filePath: String) {
        Glide.with(context)
            .load(File(filePath))
            .apply(thumbnailOptions.clone().placeholder(null)) // Sin placeholder para locales (son rápidas)
            .into(imageView)
    }

    /**
     * Carga una imagen local (File path) como portada de álbum
     */
    fun loadLocalAlbumCover(context: Context, imageView: ImageView, filePath: String) {
        Glide.with(context)
            .asBitmap()
            .load(File(filePath))
            .apply(albumImageOptions.clone().placeholder(null)) // Sin placeholder para locales
            .into(imageView)
    }

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

        val isNetworkAvailable = NetworkUtils.isNetworkAvailable(context)
        val opts = albumImageOptions.clone().onlyRetrieveFromCache(!isNetworkAvailable)

        Glide.with(context)
            .asBitmap()
            .load(url)
            .apply(opts)
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

        val ctx = fragment.context
        val opts = if (ctx != null) albumImageOptions.clone().onlyRetrieveFromCache(!NetworkUtils.isNetworkAvailable(ctx)) else albumImageOptions

        Glide.with(fragment)
            .asBitmap()
            .load(url)
            .apply(opts)
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
        if (url.isNullOrEmpty()) {
            imageView.setImageResource(R.drawable.ic_person)
            return
        }
        
        val isNetworkAvailable = NetworkUtils.isNetworkAvailable(context)
        val opts = artistImageOptions.clone().onlyRetrieveFromCache(!isNetworkAvailable)

        Glide.with(context)
            .load(url)
            .apply(opts)
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
        val ctx = fragment.context
        val opts = if (ctx != null) artistImageOptions.clone().onlyRetrieveFromCache(!NetworkUtils.isNetworkAvailable(ctx)) else artistImageOptions

        Glide.with(fragment)
            .load(url)
            .apply(opts)
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
        if (url.isNullOrEmpty()) {
            imageView.setImageResource(R.drawable.ic_music_note)
            return
        }
        
        val isNetworkAvailable = NetworkUtils.isNetworkAvailable(context)
        val opts = thumbnailOptions.clone().onlyRetrieveFromCache(!isNetworkAvailable)

        Glide.with(imageView)
            .load(url)
            .apply(opts)
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
        if (!fragment.isAdded) return
        val ctx = fragment.context
        val opts = if (ctx != null) thumbnailOptions.clone().onlyRetrieveFromCache(!NetworkUtils.isNetworkAvailable(ctx)) else thumbnailOptions

        Glide.with(fragment)
            .load(url)
            .apply(opts)
            .into(imageView)
    }

    /**
     * Precarga una imagen para mejorar la experiencia
     */
    fun preloadImage(context: Context, url: String?) {
        if (url.isNullOrEmpty()) return

        val opts = musicImageOptions.clone().onlyRetrieveFromCache(false)
        Glide.with(context)
            .load(url)
            .apply(opts)
            .preload()
    }

    fun clearMemoryCache(context: Context) {
        Glide.get(context).clearMemory()
    }

    suspend fun clearDiskCache(context: Context) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Glide.get(context).clearDiskCache()
        }
    }

    fun configureGlideCache(context: Context) {
        val memoryCacheSize = 1024 * 1024 * 50
        val diskCacheSize = 1024 * 1024 * 250L
    }

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

    fun buildArtistImageUrl(
        serverUrl: String,
        artistId: String,
        username: String,
        token: String,
        salt: String,
        size: Int = 300
    ): String {
        return "$serverUrl/rest/getCoverArt?id=artist-$artistId&u=$username&t=$token&s=$salt&v=1.16.1&c=Castafiore&f=json&size=$size"
    }
}
