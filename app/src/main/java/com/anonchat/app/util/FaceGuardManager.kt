package com.anonchat.app.util

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.abs
import kotlin.math.hypot

object FaceGuardManager {
    private const val TAG = "FaceGuardManager"
    private const val PREFS_NAME = "face_guard_prefs"
    private const val KEY_FACE_ENROLLED = "is_face_enrolled"
    private const val KEY_EYE_NOSE_RATIO = "eye_nose_ratio"
    private const val KEY_NOSE_MOUTH_RATIO = "nose_mouth_ratio"
    private const val KEY_ASPECT_RATIO = "face_aspect_ratio"
    private const val KEY_FACE_LOCK_ENABLED = "is_face_lock_enabled"

    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .setMinFaceSize(0.15f)
        .build()

    private val detector by lazy { FaceDetection.getClient(detectorOptions) }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isFaceLockEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_FACE_LOCK_ENABLED, false)
    }

    fun setFaceLockEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_FACE_LOCK_ENABLED, enabled).apply()
    }

    fun isFaceEnrolled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_FACE_ENROLLED, false)
    }

    /**
     * Enroll registered owner's facial landmark ratios
     */
    fun enrollOwnerFace(context: Context, face: Face): Boolean {
        val landmarks = extractFaceRatios(face) ?: return false
        getPrefs(context).edit().apply {
            putBoolean(KEY_FACE_ENROLLED, true)
            putFloat(KEY_EYE_NOSE_RATIO, landmarks.eyeNoseRatio)
            putFloat(KEY_NOSE_MOUTH_RATIO, landmarks.noseMouthRatio)
            putFloat(KEY_ASPECT_RATIO, landmarks.aspectRatio)
            putBoolean(KEY_FACE_LOCK_ENABLED, true)
        }.apply()
        Log.d(TAG, "Owner face enrolled successfully: $landmarks")
        return true
    }

    fun clearEnrolledFace(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    /**
     * Analyze image input and check if it matches the registered owner face
     */
    fun analyzeFace(
        context: Context,
        inputImage: InputImage,
        onResult: (isOwner: Boolean, faceCount: Int) -> Unit
    ) {
        if (!isFaceLockEnabled(context) || !isFaceEnrolled(context)) {
            // Face lock not enabled/enrolled — default to showing chats normally
            onResult(true, 0)
            return
        }

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isEmpty()) {
                    // No face detected looking at phone — hide chats
                    onResult(false, 0)
                    return@addOnSuccessListener
                }

                if (faces.size > 1) {
                    // Multiple people looking at screen — hide chats for privacy
                    onResult(false, faces.size)
                    return@addOnSuccessListener
                }

                val primaryFace = faces[0]
                val match = isMatchingOwner(context, primaryFace)
                onResult(match, 1)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Face detection processing error", e)
                onResult(false, 0)
            }
    }

    private fun isMatchingOwner(context: Context, face: Face): Boolean {
        val currentRatios = extractFaceRatios(face) ?: return false
        val prefs = getPrefs(context)

        val targetEyeNose = prefs.getFloat(KEY_EYE_NOSE_RATIO, 0f)
        val targetNoseMouth = prefs.getFloat(KEY_NOSE_MOUTH_RATIO, 0f)
        val targetAspect = prefs.getFloat(KEY_ASPECT_RATIO, 0f)

        if (targetEyeNose == 0f) return true

        val diffEyeNose = abs(currentRatios.eyeNoseRatio - targetEyeNose) / targetEyeNose
        val diffNoseMouth = abs(currentRatios.noseMouthRatio - targetNoseMouth) / targetNoseMouth
        val diffAspect = abs(currentRatios.aspectRatio - targetAspect) / targetAspect

        val isMatch = diffEyeNose < 0.25f && diffNoseMouth < 0.25f && diffAspect < 0.30f
        Log.d(TAG, "Face match check: isMatch=$isMatch (diffs: eyeNose=$diffEyeNose, noseMouth=$diffNoseMouth, aspect=$diffAspect)")
        return isMatch
    }

    private data class FaceRatios(
        val eyeNoseRatio: Float,
        val noseMouthRatio: Float,
        val aspectRatio: Float
    )

    private fun extractFaceRatios(face: Face): FaceRatios? {
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val noseBase = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
        val mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position

        val bounds = face.boundingBox
        val aspectRatio = bounds.width().toFloat() / bounds.height().coerceAtLeast(1).toFloat()

        if (leftEye != null && rightEye != null && noseBase != null) {
            val eyeDistance = hypot((rightEye.x - leftEye.x).toDouble(), (rightEye.y - leftEye.y).toDouble()).toFloat()
            val noseDistance = hypot((noseBase.x - (leftEye.x + rightEye.x) / 2f).toDouble(), (noseBase.y - (leftEye.y + rightEye.y) / 2f).toDouble()).toFloat()
            val eyeNoseRatio = eyeDistance / noseDistance.coerceAtLeast(0.01f)

            var noseMouthRatio = 1.0f
            if (mouthBottom != null) {
                val mouthDistance = hypot((mouthBottom.x - noseBase.x).toDouble(), (mouthBottom.y - noseBase.y).toDouble()).toFloat()
                noseMouthRatio = noseDistance / mouthDistance.coerceAtLeast(0.01f)
            }

            return FaceRatios(eyeNoseRatio, noseMouthRatio, aspectRatio)
        }

        return FaceRatios(1.0f, 1.0f, aspectRatio)
    }
}
