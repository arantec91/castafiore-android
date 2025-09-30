package com.arantec.castafiore.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.BitmapTransitionOptions
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.arantec.castafiore.R
import java.io.File

/**
 * Clase utilitaria para manejar la carga optimizada de imágenes con Glide
 */
object ImageLoader {

    /**
     * Opciones de caché optimizado para imágenes de música
     */
    private val musicImageOptions = RequestOptions()
        .diskCacheStrategy(DiskCacheStrategy.ALL)
        .skipMemoryCache(false)
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
        .override(200, 200)

    /**
     * Carga una imagen local (File path) con opciones de thumbnail
     */
    fun loadLocalThumbnail(context: Context, imageView: ImageView, filePath: String) {
        Glide.with(context)
            .load(File(filePath))
            .apply(thumbnailOptions)
            .transition(DrawableTransitionOptions.withCrossFade(200))
            .into(imageView)
    }

    /**
     * Carga una imagen local (File path) como portada de álbum
     */
    fun loadLocalAlbumCover(context: Context, imageView: ImageView, filePath: String) {
        Glide.with(context)
            .asBitmap()
            .load(File(filePath))
            .apply(albumImageOptions)
            .transition(BitmapTransitionOptions.withCrossFade(250))
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
        android.util.Log.d("ImageLoader", "loadAlbumCover called with URL: $url")
        
        if (url.isNullOrEmpty()) {
            android.util.Log.w("ImageLoader", "Album cover URL is null or empty")
            imageView.setImageResource(R.drawable.ic_album_placeholder)
            onError?.invoke()
            return
        }

        val isNetworkAvailable = NetworkUtils.isNetworkAvailable(context)
        android.util.Log.d("ImageLoader", "Network available for album cover: $isNetworkAvailable")
        
        val opts = albumImageOptions.clone().onlyRetrieveFromCache(!isNetworkAvailable)
        android.util.Log.d("ImageLoader", "Cache-only mode for album cover: ${!isNetworkAvailable}")

        try {
            android.util.Log.d("ImageLoader", "Starting Glide load for album cover URL: $url")
            Glide.with(context)
                .asBitmap()
                .load(url)
                .apply(opts)
                .transition(BitmapTransitionOptions.withCrossFade(250))
                .into(imageView)
            android.util.Log.d("ImageLoader", "Glide load request submitted for album cover")
        } catch (e: Exception) {
            android.util.Log.e("ImageLoader", "Exception in loadAlbumCover", e)
            imageView.setImageResource(R.drawable.ic_album_placeholder)
            onError?.invoke()
        }
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
        android.util.Log.d("ImageLoader", "loadArtistImage called with URL: $url")
        
        if (url.isNullOrEmpty()) {
            android.util.Log.w("ImageLoader", "Artist image URL is null or empty")
            imageView.setImageResource(R.drawable.ic_person)
            return
        }
        
        val isNetworkAvailable = NetworkUtils.isNetworkAvailable(context)
        android.util.Log.d("ImageLoader", "Network available for artist image: $isNetworkAvailable")
        
        val opts = artistImageOptions.clone().onlyRetrieveFromCache(!isNetworkAvailable)
        android.util.Log.d("ImageLoader", "Cache-only mode for artist image: ${!isNetworkAvailable}")

        try {
            android.util.Log.d("ImageLoader", "Starting Glide load for artist image URL: $url")
            Glide.with(context)
                .load(url)
                .apply(opts)
                .transition(DrawableTransitionOptions.withCrossFade(250))
                .into(imageView)
            android.util.Log.d("ImageLoader", "Glide load request submitted for artist image")
        } catch (e: Exception) {
            android.util.Log.e("ImageLoader", "Exception in loadArtistImage", e)
            imageView.setImageResource(R.drawable.ic_person)
        }
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
        android.util.Log.d("ImageLoader", "loadThumbnail called with URL: $url")
        
        if (url.isNullOrEmpty()) {
            android.util.Log.w("ImageLoader", "Thumbnail URL is null or empty")
            imageView.setImageResource(R.drawable.ic_music_note)
            return
        }
        
        val isNetworkAvailable = NetworkUtils.isNetworkAvailable(context)
        android.util.Log.d("ImageLoader", "Network available for thumbnail: $isNetworkAvailable")
        
        val opts = thumbnailOptions.clone().onlyRetrieveFromCache(!isNetworkAvailable)
        android.util.Log.d("ImageLoader", "Cache-only mode for thumbnail: ${!isNetworkAvailable}")
        
        try {
            android.util.Log.d("ImageLoader", "Starting Glide load for thumbnail URL: $url")
            Glide.with(imageView)
                .load(url)
                .apply(opts)
                .transition(DrawableTransitionOptions.withCrossFade(200))
                .into(imageView)
            android.util.Log.d("ImageLoader", "Glide load request submitted for thumbnail")
        } catch (e: Exception) {
            android.util.Log.e("ImageLoader", "Exception in loadThumbnail", e)
            imageView.setImageResource(R.drawable.ic_music_note)
        }
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
            .transition(DrawableTransitionOptions.withCrossFade(200))
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
        return "$serverUrl/rest/getCoverArt.view?id=$artistId&u=$username&t=$token&s=$salt&v=1.16.1&c=Castafiore&size=$size"
    }
}
