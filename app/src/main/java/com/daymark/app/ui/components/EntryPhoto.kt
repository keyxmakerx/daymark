package com.daymark.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.daymark.app.data.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shows an entry's attached photo (resolved from its relative [photoPath]) as a rounded
 * thumbnail. Decoding happens off the main thread; nothing here touches the network — the file
 * is read straight from the app's private storage.
 */
@Composable
fun EntryPhoto(
    photoPath: String,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    cornerRadius: Dp = 10.dp,
) {
    val context = LocalContext.current
    var bitmap by remember(photoPath) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(photoPath) {
        bitmap = withContext(Dispatchers.IO) {
            val file = PhotoStore.fileFor(context, photoPath)
            if (file.exists()) {
                runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
            } else {
                null
            }
        }
    }

    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = "Entry photo",
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(cornerRadius)),
        )
    }
}
