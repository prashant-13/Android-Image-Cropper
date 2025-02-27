package com.canhub.cropper

import android.Manifest
import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Parcel
import android.os.Parcelable
import android.provider.MediaStore
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.canhub.cropper.CropImageView.CropResult
import com.canhub.cropper.CropImageView.CropShape
import com.canhub.cropper.CropImageView.Guidelines
import com.canhub.cropper.CropImageView.RequestSizeOptions
import com.canhub.cropper.common.CommonValues
import com.canhub.cropper.common.CommonVersionCheck
import com.canhub.cropper.common.CommonVersionCheck.isAtLeastQ29
import java.io.File
import java.util.ArrayList

/**
 * Helper to simplify crop image work like starting pick-image acitvity and handling camera/gallery
 * intents.<br></br>
 * The goal of the helper is to simplify the starting and most-common usage of image cropping and
 * not all porpose all possible scenario one-to-rule-them-all code base. So feel free to use it as
 * is and as a wiki to make your own.<br></br>
 * Added value you get out-of-the-box is some edge case handling that you may miss otherwise, like
 * the stupid-ass Android camera result URI that may differ from version to version and from device
 * to device.
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
object CropImage {

    /**
     * The key used to pass crop image source URI to [CropImageActivity].
     */
    const val CROP_IMAGE_EXTRA_SOURCE = "CROP_IMAGE_EXTRA_SOURCE"

    /**
     * The key used to pass crop image options to [CropImageActivity].
     */
    const val CROP_IMAGE_EXTRA_OPTIONS = "CROP_IMAGE_EXTRA_OPTIONS"

    /**
     * The key used to pass crop image bundle data to [CropImageActivity].
     */
    const val CROP_IMAGE_EXTRA_BUNDLE = "CROP_IMAGE_EXTRA_BUNDLE"

    /**
     * The key used to pass crop image result data back from [CropImageActivity].
     */
    const val CROP_IMAGE_EXTRA_RESULT = "CROP_IMAGE_EXTRA_RESULT"

    /**
     * The request code used to start pick image activity to be used on result to identify the this
     * specific request.
     */
    const val PICK_IMAGE_CHOOSER_REQUEST_CODE = 200

    /**
     * The request code used to request permission to pick image from external storage.
     */
    const val PICK_IMAGE_PERMISSIONS_REQUEST_CODE = 201

    /**
     * The request code used to request permission to capture image from camera.
     */
    const val CAMERA_CAPTURE_PERMISSIONS_REQUEST_CODE = 2011

    /**
     * The request code used to start [CropImageActivity] to be used on result to identify the
     * this specific request.
     */
    const val CROP_IMAGE_ACTIVITY_REQUEST_CODE = 203

    /**
     * The result code used to return error from [CropImageActivity].
     */
    const val CROP_IMAGE_ACTIVITY_RESULT_ERROR_CODE = 204

    /**
     * Create a new bitmap that has all pixels beyond the oval shape transparent. Old bitmap is
     * recycled.
     */
    fun toOvalBitmap(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val color = -0xbdbdbe
        val paint = Paint()
        paint.isAntiAlias = true
        canvas.drawARGB(0, 0, 0, 0)
        paint.color = color
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawOval(rect, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        bitmap.recycle()
        return output
    }

    /**
     * Start an activity to get image for cropping using chooser intent that will have all the
     * available applications for the device like camera (MyCamera), gallery (Photos), store apps
     * (Dropbox), etc.<br></br>
     * Use "pick_image_intent_chooser_title" string resource to override pick chooser title.
     *
     * @param activity the activity to be used to start activity from
     */
    fun startPickImageActivity(activity: Activity) {
        activity.startActivityForResult(
            getPickImageChooserIntent(activity), PICK_IMAGE_CHOOSER_REQUEST_CODE
        )
    }

    /**
     * Same as [startPickImageActivity][.startPickImageActivity] method but instead of
     * being called and returning to an Activity, this method can be called and return to a Fragment.
     *
     * @param context  The Fragments context. Use getContext()
     * @param fragment The calling Fragment to start and return the image to
     */
    fun startPickImageActivity(context: Context, fragment: Fragment) {
        fragment.startActivityForResult(
            getPickImageChooserIntent(context), PICK_IMAGE_CHOOSER_REQUEST_CODE
        )
    }

    /**
     * Create a chooser intent to select the source to get image from.<br></br>
     * The source can be camera's (ACTION_IMAGE_CAPTURE) or gallery's (ACTION_GET_CONTENT).<br></br>
     * All possible sources are added to the intent chooser.<br></br>
     * Use "pick_image_intent_chooser_title" string resource to override chooser title.
     *
     * @param context used to access Android APIs, like content resolve, it is your
     * activity/fragment/widget.
     */
    fun getPickImageChooserIntent(context: Context): Intent {
        return getPickImageChooserIntent(
            context = context,
            title = context.getString(R.string.pick_image_intent_chooser_title),
            includeDocuments = false,
            includeCamera = true
        )
    }

    /**
     * Create a chooser intent to select the source to get image from.<br></br>
     * The source can be camera's (ACTION_IMAGE_CAPTURE) or gallery's (ACTION_GET_CONTENT).<br></br>
     * All possible sources are added to the intent chooser.
     *
     * @param context          used to access Android APIs, like content resolve, it is your
     * activity/fragment/widget.
     * @param title            the title to use for the chooser UI
     * @param includeDocuments if to include KitKat documents activity containing all sources
     * @param includeCamera    if to include camera intents
     */
    fun getPickImageChooserIntent(
        context: Context,
        title: CharSequence?,
        includeDocuments: Boolean, // todo, remove this. Should always be false for image to crop.
        includeCamera: Boolean,
    ): Intent {
        val allIntents: MutableList<Intent> = ArrayList()
        val packageManager = context.packageManager
        // collect all camera intents if Camera permission is available
        if (!isExplicitCameraPermissionRequired(context) && includeCamera) {
            allIntents.addAll(getCameraIntents(context, packageManager))
        }
        allIntents.addAll(
            getGalleryIntents(
                packageManager,
                Intent.ACTION_GET_CONTENT,
                includeDocuments
            )
        )
        // Create a chooser from the main  intent
        val chooserIntent = Intent.createChooser(allIntents.removeAt(allIntents.size - 1), title)
        // Add all other intents
        chooserIntent.putExtra(
            Intent.EXTRA_INITIAL_INTENTS, allIntents.toTypedArray<Parcelable>()
        )
        return chooserIntent
    }

    /**
     * Get the main Camera intent for capturing image using device camera app. If the outputFileUri is
     * null, a default Uri will be created with [.getCaptureImageOutputUri], so then
     * you will be able to get the pictureUri using [.getPickImageResultUri].
     * Otherwise, it is just you use the Uri passed to this method.
     *
     * @param context       used to access Android APIs, like content resolve, it is your
     * activity/fragment/widget.
     * @param outputFileUri the Uri where the picture will be placed.
     */
    // todo this need be public?
    fun getCameraIntent(
        context: Context,
        outputFileUri: Uri?,
    ): Intent {
        var newOutputFileUri = outputFileUri
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (newOutputFileUri == null) {
            newOutputFileUri = getCaptureImageOutputUri(context)
        }
        intent.putExtra(MediaStore.EXTRA_OUTPUT, newOutputFileUri)
        return intent
    }

    /**
     * Get all Camera intents for capturing image using device camera apps.
     */
    // todo this need be public?
    fun getCameraIntents(
        context: Context,
        packageManager: PackageManager,
    ): List<Intent> {
        val allIntents: MutableList<Intent> = ArrayList()
        // Determine Uri of camera image to  save.
        val outputFileUri = getCaptureImageOutputUri(context)
        val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val listCam = packageManager.queryIntentActivities(captureIntent, 0)
        for (res in listCam) {
            val intent = Intent(captureIntent)
            intent.component = ComponentName(res.activityInfo.packageName, res.activityInfo.name)
            intent.setPackage(res.activityInfo.packageName)
            intent.putExtra(MediaStore.EXTRA_OUTPUT, outputFileUri)
            allIntents.add(intent)
        }
        // Just in case queryIntentActivities returns emptyList
        if (allIntents.isEmpty()) allIntents.add(captureIntent)
        return allIntents
    }

    /**
     * Get all Gallery intents for getting image from one of the apps of the device that handle
     * images.
     */
    // todo this need be public?
    fun getGalleryIntents(
        packageManager: PackageManager,
        action: String?,
        includeDocuments: Boolean,
    ): List<Intent> {
        val intents: MutableList<Intent> = ArrayList()
        val galleryIntent = Intent(action)
        galleryIntent.type = if (includeDocuments) "*/*" else "image/*"
        galleryIntent.addCategory(Intent.CATEGORY_OPENABLE)
        var listGallery = packageManager.queryIntentActivities(galleryIntent, 0)
        if (isAtLeastQ29() && listGallery.size > 2) {
            // Workaround for the bug that only 2 items are shown in Android Q
            // // https://issuetracker.google.com/issues/134367295
            // Trying to pick best match items
            listGallery.sortWith { o1: ResolveInfo, _: ResolveInfo? ->
                val packageName = o1.activityInfo.packageName
                if (packageName.contains("photo")) return@sortWith -1
                if (packageName.contains("gallery")) return@sortWith -1
                if (packageName.contains("album")) return@sortWith -1
                if (packageName.contains("media")) return@sortWith -1
                0
            }
            listGallery = listGallery.subList(0, 2)
        }
        for (res in listGallery) {
            val intent = Intent(galleryIntent)
            intent.component = ComponentName(res.activityInfo.packageName, res.activityInfo.name)
            intent.setPackage(res.activityInfo.packageName)
            intents.add(intent)
        }
        // Just in case queryIntentActivities returns emptyList
        if (intents.isEmpty()) {
            intents.add(galleryIntent)
        }
        return intents
    }

    /**
     * Check if explicetly requesting camera permission is required.<br></br>
     * It is required in Android Marshmellow and above if "CAMERA" permission is requested in the
     * manifest.<br></br>
     * See [StackOverflow
 * question](http://stackoverflow.com/questions/32789027/android-m-camera-intent-permission-bug).
     */
    // todo, if true, return error saying permission is needed.
    // on settings give option to library asked permission (default true)
    fun isExplicitCameraPermissionRequired(context: Context): Boolean = (
        CommonVersionCheck.isAtLeastM23() &&
            hasPermissionInManifest(context, "android.permission.CAMERA") &&
            (context.checkSelfPermission(Manifest.permission.CAMERA) != PERMISSION_GRANTED)
        )

    /**
     * Check if the app requests a specific permission in the manifest.
     *
     * @param permissionName the permission to check
     * @return true - the permission in requested in manifest, false - not.
     */
    fun hasPermissionInManifest(
        context: Context,
        permissionName: String,
    ): Boolean {
        val packageName = context.packageName
        try {
            val packageInfo =
                context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            val declaredPermissions = packageInfo.requestedPermissions
            if (declaredPermissions != null && declaredPermissions.isNotEmpty()) {
                for (p in declaredPermissions) {
                    if (p.equals(permissionName, ignoreCase = true)) return true
                }
            }
        } catch (e: PackageManager.NameNotFoundException) {
        }
        return false
    }

    /**
     * Get URI to image received from capture by camera.
     *
     * @param context used to access Android APIs, like content resolve, it is your
     * activity/fragment/widget.
     */
    fun getCaptureImageOutputUri(context: Context): Uri {
        val outputFileUri: Uri
        val getImage: File?
        // We have this because of a HUAWEI path bug when we use getUriForFile
        if (isAtLeastQ29()) {
            getImage = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            outputFileUri = try {
                FileProvider.getUriForFile(
                    context,
                    context.packageName + CommonValues.authority,
                    File(getImage!!.path, "pickImageResult.jpeg")
                )
            } catch (e: Exception) {
                Uri.fromFile(File(getImage!!.path, "pickImageResult.jpeg"))
            }
        } else {
            getImage = context.externalCacheDir
            outputFileUri = Uri.fromFile(File(getImage!!.path, "pickImageResult.jpeg"))
        }
        return outputFileUri
    }

    /**
     * Get the URI of the selected image from [.getPickImageChooserIntent].<br></br>
     * Will return the correct URI for camera and gallery image.
     *
     * @param context used to access Android APIs, like content resolve, it is your
     * activity/fragment/widget.
     * @param data    the returned data of the activity result
     */
    fun getPickImageResultUri(context: Context, data: Intent?): Uri {
        var isCamera = true
        if (data != null && data.data != null) {
            val action = data.action
            isCamera = action != null && action == MediaStore.ACTION_IMAGE_CAPTURE
        }
        return if (isCamera || data!!.data == null) getCaptureImageOutputUri(context) else data.data!!
    }

    /**
     * Check if the given picked image URI requires READ_EXTERNAL_STORAGE permissions.<br></br>
     * Only relevant for API version 23 and above and not required for all URI's depends on the
     * implementation of the app that was used for picking the image. So we just test if we can open
     * the stream or do we get an exception when we try, Android is awesome.
     *
     * @param context used to access Android APIs, like content resolve, it is your
     * activity/fragment/widget.
     * @param uri     the result URI of image pick.
     * @return true - required permission are not granted, false - either no need for permissions or
     * they are granted
     */
    fun isReadExternalStoragePermissionsRequired(
        context: Context,
        uri: Uri,
    ): Boolean =
        CommonVersionCheck.isAtLeastM23() &&
            (context.checkSelfPermission(READ_EXTERNAL_STORAGE) != PERMISSION_GRANTED) &&
            isUriRequiresPermissions(context, uri)

    /**
     * Test if we can open the given Android URI to test if permission required error is thrown.<br></br>
     * Only relevant for API version 23 and above.
     *
     * @param context used to access Android APIs, like content resolve, it is your
     * activity/fragment/widget.
     * @param uri     the result URI of image pick.
     */
    fun isUriRequiresPermissions(context: Context, uri: Uri): Boolean {
        return try {
            val resolver = context.contentResolver
            val stream = resolver.openInputStream(uri)
            stream?.close()
            false
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Create [ActivityBuilder] instance to open image picker for cropping and then start [ ] to crop the selected image.<br></br>
     * Result will be received in onActivityResult(int, int, Intent) and can be
     * retrieved using [.getActivityResult].
     *
     * @return builder for Crop Image Activity
     */
    fun activity(): ActivityBuilder {
        return ActivityBuilder(null)
    }

    /**
     * Create [ActivityBuilder] instance to start [CropImageActivity] to crop the given
     * image.<br></br>
     * Result will be received in onActivityResult(int, int, Intent) and can be
     * retrieved using [.getActivityResult].
     *
     * @param uri the image Android uri source to crop or null to start a picker
     * @return builder for Crop Image Activity
     */
    fun activity(uri: Uri?): ActivityBuilder {
        return ActivityBuilder(uri)
    }

    /**
     * Get [CropImageActivity] result data object for crop image activity started using [ ][.activity].
     *
     * @param data result data intent as received in onActivityResult(int, int, Intent).
     * @return Crop Image Activity Result object or null if none exists
     */
    // TODO don't return null
    fun getActivityResult(data: Intent?): ActivityResult? =
        data?.getParcelableExtra<Parcelable>(CROP_IMAGE_EXTRA_RESULT) as? ActivityResult?

    /**
     * Builder used for creating Image Crop Activity by user request.
     *
     * @param mSource The image to crop source Android uri.
     */
    class ActivityBuilder(private val mSource: Uri?) {
        /**
         * Options for image crop UX
         */
        private val mOptions: CropImageOptions = CropImageOptions()

        /**
         * Get [CropImageActivity] intent to start the activity.
         */
        fun getIntent(context: Context): Intent {
            return getIntent(context, CropImageActivity::class.java)
        }

        /**
         * Get [CropImageActivity] intent to start the activity.
         */
        fun getIntent(context: Context, cls: Class<*>?): Intent {
            mOptions.validate()
            val intent = Intent()
            intent.setClass(context, cls!!)
            val bundle = Bundle()
            bundle.putParcelable(CROP_IMAGE_EXTRA_SOURCE, mSource)
            bundle.putParcelable(CROP_IMAGE_EXTRA_OPTIONS, mOptions)
            intent.putExtra(CROP_IMAGE_EXTRA_BUNDLE, bundle)
            return intent
        }

        /**
         * Start [CropImageActivity].
         *
         * @param activity activity to receive result
         */
        fun start(activity: Activity) {
            mOptions.validate()
            activity.startActivityForResult(getIntent(activity), CROP_IMAGE_ACTIVITY_REQUEST_CODE)
        }

        /**
         * Start [CropImageActivity].
         *
         * @param activity activity to receive result
         */
        fun start(activity: Activity, cls: Class<*>?) {
            mOptions.validate()
            activity.startActivityForResult(
                getIntent(activity, cls),
                CROP_IMAGE_ACTIVITY_REQUEST_CODE
            )
        }

        /**
         * Start [CropImageActivity].
         *
         * @param fragment fragment to receive result
         */
        fun start(context: Context, fragment: Fragment) {
            fragment.startActivityForResult(getIntent(context), CROP_IMAGE_ACTIVITY_REQUEST_CODE)
        }

        /**
         * Start [CropImageActivity].
         *
         * @param fragment fragment to receive result
         */
        @RequiresApi(api = Build.VERSION_CODES.HONEYCOMB)
        fun start(context: Context, fragment: android.app.Fragment) {
            fragment.startActivityForResult(getIntent(context), CROP_IMAGE_ACTIVITY_REQUEST_CODE)
        }

        /**
         * Start [CropImageActivity].
         *
         * @param fragment fragment to receive result
         */
        fun start(
            context: Context,
            fragment: Fragment,
            cls: Class<*>?,
        ) {
            fragment.startActivityForResult(
                getIntent(context, cls),
                CROP_IMAGE_ACTIVITY_REQUEST_CODE
            )
        }

        /**
         * Start [CropImageActivity].
         *
         * @param fragment fragment to receive result
         */
        @RequiresApi(api = Build.VERSION_CODES.HONEYCOMB)
        fun start(
            context: Context,
            fragment: android.app.Fragment,
            cls: Class<*>?,
        ) {
            fragment.startActivityForResult(
                getIntent(context, cls),
                CROP_IMAGE_ACTIVITY_REQUEST_CODE
            )
        }

        /**
         * The shape of the cropping window.<br></br>
         * To set square/circle crop shape set aspect ratio to 1:1.<br></br>
         * *Default: RECTANGLE*
         *
         * When setting RECTANGLE_VERTICAL_ONLY or RECTANGLE_HORIZONTAL_ONLY you may also want to
         * use a free aspect ratio (to allow the crop window to change in the desired dimension
         * whilst staying the same in the other dimension) and have the initial crop window cover
         * the entire image (so that the crop window has no space to move in the other dimension).
         * These can be done with
         * [ActivityBuilder.setFixAspectRatio] } (with argument `false`) and
         * [ActivityBuilder.setInitialCropWindowPaddingRatio] (with argument `0f).
         */
        fun setCropShape(cropShape: CropShape): ActivityBuilder {
            mOptions.cropShape = cropShape
            return this
        }

        /**
         * An edge of the crop window will snap to the corresponding edge of a specified bounding box
         * when the crop window edge is less than or equal to this distance (in pixels) away from the
         * bounding box edge (in pixels).<br></br>
         * *Default: 3dp*
         */
        fun setSnapRadius(snapRadius: Float): ActivityBuilder {
            mOptions.snapRadius = snapRadius
            return this
        }

        /**
         * The radius of the touchable area around the handle (in pixels).<br></br>
         * We are basing this value off of the recommended 48dp Rhythm.<br></br>
         * See: http://developer.android.com/design/style/metrics-grids.html#48dp-rhythm<br></br>
         * *Default: 48dp*
         */
        fun setTouchRadius(touchRadius: Float): ActivityBuilder {
            mOptions.touchRadius = touchRadius
            return this
        }

        /**
         * whether the guidelines should be on, off, or only showing when resizing.<br></br>
         * *Default: ON_TOUCH*
         */
        fun setGuidelines(guidelines: Guidelines): ActivityBuilder {
            mOptions.guidelines = guidelines
            return this
        }

        /**
         * The initial scale type of the image in the crop image view<br></br>
         * *Default: FIT_CENTER*
         */
        fun setScaleType(scaleType: CropImageView.ScaleType): ActivityBuilder {
            mOptions.scaleType = scaleType
            return this
        }

        /**
         * if to show crop overlay UI what contains the crop window UI surrounded by background over the
         * cropping image.<br></br>
         * *default: true, may disable for animation or frame transition.*
         */
        fun setShowCropOverlay(showCropOverlay: Boolean): ActivityBuilder {
            mOptions.showCropOverlay = showCropOverlay
            return this
        }

        /**
         * if auto-zoom functionality is enabled.<br></br>
         * default: true.
         */
        fun setAutoZoomEnabled(autoZoomEnabled: Boolean): ActivityBuilder {
            mOptions.autoZoomEnabled = autoZoomEnabled
            return this
        }

        /**
         * if multi touch functionality is enabled.<br></br>
         * default: true.
         */
        fun setMultiTouchEnabled(multiTouchEnabled: Boolean): ActivityBuilder {
            mOptions.multiTouchEnabled = multiTouchEnabled
            return this
        }

        /**
         * if the crop window can be moved by dragging the center.<br></br>
         * default: true
         */
        fun setCenterMoveEnabled(centerMoveEnabled: Boolean): ActivityBuilder {
            mOptions.centerMoveEnabled = centerMoveEnabled
            return this
        }

        /**
         * The max zoom allowed during cropping.<br></br>
         * *Default: 4*
         */
        fun setMaxZoom(maxZoom: Int): ActivityBuilder {
            mOptions.maxZoom = maxZoom
            return this
        }

        /**
         * The initial crop window padding from image borders in percentage of the cropping image
         * dimensions.<br></br>
         * *Default: 0.1*
         */
        fun setInitialCropWindowPaddingRatio(initialCropWindowPaddingRatio: Float): ActivityBuilder {
            mOptions.initialCropWindowPaddingRatio = initialCropWindowPaddingRatio
            return this
        }

        /**
         * whether the width to height aspect ratio should be maintained or free to change.<br></br>
         * *Default: false*
         */
        fun setFixAspectRatio(fixAspectRatio: Boolean): ActivityBuilder {
            mOptions.fixAspectRatio = fixAspectRatio
            return this
        }

        /**
         * the X,Y value of the aspect ratio.<br></br>
         * Also sets fixes aspect ratio to TRUE.<br></br>
         * *Default: 1/1*
         *
         * @param aspectRatioX the width
         * @param aspectRatioY the height
         */
        fun setAspectRatio(aspectRatioX: Int, aspectRatioY: Int): ActivityBuilder {
            mOptions.aspectRatioX = aspectRatioX
            mOptions.aspectRatioY = aspectRatioY
            mOptions.fixAspectRatio = true
            return this
        }

        /**
         * the thickness of the guidelines lines (in pixels).<br></br>
         * *Default: 3dp*
         */
        fun setBorderLineThickness(borderLineThickness: Float): ActivityBuilder {
            mOptions.borderLineThickness = borderLineThickness
            return this
        }

        /**
         * the color of the guidelines lines.<br></br>
         * *Default: Color.argb(170, 255, 255, 255)*
         */
        fun setBorderLineColor(borderLineColor: Int): ActivityBuilder {
            mOptions.borderLineColor = borderLineColor
            return this
        }

        /**
         * thickness of the corner line (in pixels).<br></br>
         * *Default: 2dp*
         */
        fun setBorderCornerThickness(borderCornerThickness: Float): ActivityBuilder {
            mOptions.borderCornerThickness = borderCornerThickness
            return this
        }

        /**
         * the offset of corner line from crop window border (in pixels).<br></br>
         * *Default: 5dp*
         */
        fun setBorderCornerOffset(borderCornerOffset: Float): ActivityBuilder {
            mOptions.borderCornerOffset = borderCornerOffset
            return this
        }

        /**
         * the length of the corner line away from the corner (in pixels).<br></br>
         * *Default: 14dp*
         */
        fun setBorderCornerLength(borderCornerLength: Float): ActivityBuilder {
            mOptions.borderCornerLength = borderCornerLength
            return this
        }

        /**
         * the color of the corner line.<br></br>
         * *Default: WHITE*
         */
        fun setBorderCornerColor(borderCornerColor: Int): ActivityBuilder {
            mOptions.borderCornerColor = borderCornerColor
            return this
        }

        /**
         * the thickness of the guidelines lines (in pixels).<br></br>
         * *Default: 1dp*
         */
        fun setGuidelinesThickness(guidelinesThickness: Float): ActivityBuilder {
            mOptions.guidelinesThickness = guidelinesThickness
            return this
        }

        /**
         * the color of the guidelines lines.<br></br>
         * *Default: Color.argb(170, 255, 255, 255)*
         */
        fun setGuidelinesColor(guidelinesColor: Int): ActivityBuilder {
            mOptions.guidelinesColor = guidelinesColor
            return this
        }

        /**
         * the color of the overlay background around the crop window cover the image parts not in the
         * crop window.<br></br>
         * *Default: Color.argb(119, 0, 0, 0)*
         */
        fun setBackgroundColor(backgroundColor: Int): ActivityBuilder {
            mOptions.backgroundColor = backgroundColor
            return this
        }

        /**
         * the min size the crop window is allowed to be (in pixels).<br></br>
         * *Default: 42dp, 42dp*
         */
        fun setMinCropWindowSize(
            minCropWindowWidth: Int,
            minCropWindowHeight: Int
        ): ActivityBuilder {
            mOptions.minCropWindowWidth = minCropWindowWidth
            mOptions.minCropWindowHeight = minCropWindowHeight
            return this
        }

        /**
         * the min size the resulting cropping image is allowed to be, affects the cropping window
         * limits (in pixels).<br></br>
         * *Default: 40px, 40px*
         */
        fun setMinCropResultSize(
            minCropResultWidth: Int,
            minCropResultHeight: Int
        ): ActivityBuilder {
            mOptions.minCropResultWidth = minCropResultWidth
            mOptions.minCropResultHeight = minCropResultHeight
            return this
        }

        /**
         * the max size the resulting cropping image is allowed to be, affects the cropping window
         * limits (in pixels).<br></br>
         * *Default: 99999, 99999*
         */
        fun setMaxCropResultSize(
            maxCropResultWidth: Int,
            maxCropResultHeight: Int
        ): ActivityBuilder {
            mOptions.maxCropResultWidth = maxCropResultWidth
            mOptions.maxCropResultHeight = maxCropResultHeight
            return this
        }

        /**
         * the title of the [CropImageActivity].<br></br>
         * *Default: ""*
         */
        fun setActivityTitle(activityTitle: CharSequence?): ActivityBuilder {
            mOptions.activityTitle = activityTitle!!
            return this
        }

        /**
         * the color to use for action bar items icons.<br></br>
         * *Default: NONE*
         */
        fun setActivityMenuIconColor(activityMenuIconColor: Int): ActivityBuilder {
            mOptions.activityMenuIconColor = activityMenuIconColor
            return this
        }

        /**
         * the Android Uri to save the cropped image to.<br></br>
         * *Default: NONE, will create a temp file*
         */
        fun setOutputUri(outputUri: Uri?): ActivityBuilder {
            mOptions.outputUri = outputUri
            return this
        }

        /**
         * the compression format to use when writting the image.<br></br>
         * *Default: JPEG*
         */
        fun setOutputCompressFormat(outputCompressFormat: CompressFormat?): ActivityBuilder {
            mOptions.outputCompressFormat = outputCompressFormat!!
            return this
        }

        /**
         * the quility (if applicable) to use when writting the image (0 - 100).<br></br>
         * *Default: 90*
         */
        fun setOutputCompressQuality(outputCompressQuality: Int): ActivityBuilder {
            mOptions.outputCompressQuality = outputCompressQuality
            return this
        }

        /**
         * the size to resize the cropped image to.<br></br>
         * Uses [CropImageView.RequestSizeOptions.RESIZE_INSIDE] option.<br></br>
         * *Default: 0, 0 - not set, will not resize*
         */
        fun setRequestedSize(reqWidth: Int, reqHeight: Int): ActivityBuilder {
            return setRequestedSize(reqWidth, reqHeight, RequestSizeOptions.RESIZE_INSIDE)
        }

        /**
         * the size to resize the cropped image to.<br></br>
         * *Default: 0, 0 - not set, will not resize*
         */
        fun setRequestedSize(
            reqWidth: Int,
            reqHeight: Int,
            options: RequestSizeOptions?,
        ): ActivityBuilder {
            mOptions.outputRequestWidth = reqWidth
            mOptions.outputRequestHeight = reqHeight
            mOptions.outputRequestSizeOptions = options!!
            return this
        }

        /**
         * if the result of crop image activity should not save the cropped image bitmap.<br></br>
         * Used if you want to crop the image manually and need only the crop rectangle and rotation
         * data.<br></br>
         * *Default: false*
         */
        fun setNoOutputImage(noOutputImage: Boolean): ActivityBuilder {
            mOptions.noOutputImage = noOutputImage
            return this
        }

        /**
         * the initial rectangle to set on the cropping image after loading.<br></br>
         * *Default: NONE - will initialize using initial crop window padding ratio*
         */
        fun setInitialCropWindowRectangle(initialCropWindowRectangle: Rect?): ActivityBuilder {
            mOptions.initialCropWindowRectangle = initialCropWindowRectangle
            return this
        }

        /**
         * the initial rotation to set on the cropping image after loading (0-360 degrees clockwise).
         * <br></br>
         * *Default: NONE - will read image exif data*
         */
        fun setInitialRotation(initialRotation: Int): ActivityBuilder {
            mOptions.initialRotation = (initialRotation + 360) % 360
            return this
        }

        /**
         * if to allow rotation during cropping.<br></br>
         * *Default: true*
         */
        fun setAllowRotation(allowRotation: Boolean): ActivityBuilder {
            mOptions.allowRotation = allowRotation
            return this
        }

        /**
         * if to allow flipping during cropping.<br></br>
         * *Default: true*
         */
        fun setAllowFlipping(allowFlipping: Boolean): ActivityBuilder {
            mOptions.allowFlipping = allowFlipping
            return this
        }

        /**
         * if to allow counter-clockwise rotation during cropping.<br></br>
         * Note: if rotation is disabled this option has no effect.<br></br>
         * *Default: false*
         */
        fun setAllowCounterRotation(allowCounterRotation: Boolean): ActivityBuilder {
            mOptions.allowCounterRotation = allowCounterRotation
            return this
        }

        /**
         * The amount of degreees to rotate clockwise or counter-clockwise (0-360).<br></br>
         * *Default: 90*
         */
        fun setRotationDegrees(rotationDegrees: Int): ActivityBuilder {
            mOptions.rotationDegrees = (rotationDegrees + 360) % 360
            return this
        }

        /**
         * whether the image should be flipped horizontally.<br></br>
         * *Default: false*
         */
        fun setFlipHorizontally(flipHorizontally: Boolean): ActivityBuilder {
            mOptions.flipHorizontally = flipHorizontally
            return this
        }

        /**
         * whether the image should be flipped vertically.<br></br>
         * *Default: false*
         */
        fun setFlipVertically(flipVertically: Boolean): ActivityBuilder {
            mOptions.flipVertically = flipVertically
            return this
        }

        /**
         * optional, set crop menu crop button title.<br></br>
         * *Default: null, will use resource string: crop_image_menu_crop*
         */
        fun setCropMenuCropButtonTitle(title: CharSequence?): ActivityBuilder {
            mOptions.cropMenuCropButtonTitle = title
            return this
        }

        /**
         * Image resource id to use for crop icon instead of text.<br></br>
         * *Default: 0*
         */
        fun setCropMenuCropButtonIcon(@DrawableRes drawableResource: Int): ActivityBuilder {
            mOptions.cropMenuCropButtonIcon = drawableResource
            return this
        }
    }

    /**
     * Result data of Crop Image Activity.
     */
    open class ActivityResult : CropResult, Parcelable {

        constructor(
            originalUri: Uri?,
            uri: Uri?,
            error: Exception?,
            cropPoints: FloatArray?,
            cropRect: Rect?,
            rotation: Int,
            wholeImageRect: Rect?,
            sampleSize: Int
        ) : super(
            originalBitmap = null,
            originalUri = originalUri,
            bitmap = null,
            uri = uri,
            error = error,
            cropPoints = cropPoints!!,
            cropRect = cropRect,
            wholeImageRect = wholeImageRect,
            rotation = rotation,
            sampleSize = sampleSize
        )

        protected constructor(`in`: Parcel) : super(
            originalBitmap = null,
            originalUri = `in`.readParcelable<Parcelable>(Uri::class.java.classLoader) as Uri?,
            bitmap = null,
            uri = `in`.readParcelable<Parcelable>(Uri::class.java.classLoader) as Uri?,
            error = `in`.readSerializable() as Exception?,
            cropPoints = `in`.createFloatArray()!!,
            cropRect = `in`.readParcelable<Parcelable>(Rect::class.java.classLoader) as Rect?,
            wholeImageRect = `in`.readParcelable<Parcelable>(Rect::class.java.classLoader) as Rect?,
            rotation = `in`.readInt(),
            sampleSize = `in`.readInt()
        )

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeParcelable(originalUri, flags)
            dest.writeParcelable(uri, flags)
            dest.writeSerializable(error)
            dest.writeFloatArray(cropPoints)
            dest.writeParcelable(cropRect, flags)
            dest.writeParcelable(wholeImageRect, flags)
            dest.writeInt(rotation)
            dest.writeInt(sampleSize)
        }

        override fun describeContents(): Int = 0

        companion object {

            @JvmField
            val CREATOR: Parcelable.Creator<ActivityResult?> =
                object : Parcelable.Creator<ActivityResult?> {
                    override fun createFromParcel(`in`: Parcel): ActivityResult =
                        ActivityResult(`in`)

                    override fun newArray(size: Int): Array<ActivityResult?> = arrayOfNulls(size)
                }
        }
    }
}
