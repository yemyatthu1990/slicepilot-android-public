package world.yemyat.slicepilot.ui.components

import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Show a View that something else owns — the ViewModel's camera `SurfaceView`, the
 * print-setup `ModelPreviewView`, the scanner's `PreviewView` — at this place in the
 * Compose tree.
 *
 * `AndroidView` calls `addView()` the moment its holder is built, and Compose inserts
 * a new node *before* it releases the one the new one replaces. So a single View
 * instance can never be handed to two slots in the same frame: the second `addView`
 * throws
 * `IllegalStateException: The specified child already has a parent. You must call
 * removeView() on the child's parent first.` That is one Crashlytics issue with two
 * faces — the preview swapping between the setup sheet and its fullscreen dialog
 * (first seen 1.8.1), and the camera preview swapping between the Printer card and
 * `Screen.CameraFull` (1.9.0) — both `AndroidViewHolder.<init>` in the stack.
 *
 * Wrapping the old slot in `if (!fullscreen)` is not the fix: the two slots are still
 * exchanged in one frame, so the order never changes. Detaching the View from its old
 * parent here, before handing it to the new holder, is.
 */
@Composable
fun SharedAndroidView(
    view: View,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = {
            (view.parent as? ViewGroup)?.removeView(view)
            view
        },
        modifier = modifier,
    )
}
