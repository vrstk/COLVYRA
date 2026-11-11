package com.colvyra.app

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import coil.size.Precision

/** Only request preview-sized pixels in lists and grids. Full-screen uses its own request. */
@Composable
internal fun localThumbnailRequest(uri: Uri, edgePx: Int): ImageRequest {
    val context = LocalContext.current
    return remember(context, uri, edgePx) {
        ImageRequest.Builder(context)
            .data(uri)
            .size(edgePx, edgePx)
            .precision(Precision.INEXACT)
            .build()
    }
}
