package com.android.mycamera.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import java.io.IOException;

public final class MediaOrientationUtils {

    private static final String TAG = "MediaOrientationUtils";
    /** Soft cap so 36M/64M JPEGs never become ~250MB ARGB bitmaps on screen. */
    public static final int DEFAULT_MAX_DISPLAY_SIDE = 4096;

    private MediaOrientationUtils() {
    }

    public static Bitmap decodeOrientedImage(String path, BitmapFactory.Options options) {
        Bitmap bitmap = BitmapFactory.decodeFile(path, options);
        if (bitmap == null) return null;
        return applyExifOrientation(path, bitmap);
    }

    /**
     * Decode still image downsampled for on-screen preview/thumbnail.
     * Full 64M (9216x6912) ARGB would be ~254MB and crash Canvas.
     */
    public static Bitmap decodeOrientedImageForDisplay(String path, int maxSide) {
        if (path == null) {
            return null;
        }
        if (maxSide <= 0) {
            maxSide = DEFAULT_MAX_DISPLAY_SIDE;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxSide);
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inJustDecodeBounds = false;
        Log.d(TAG, "decodeOrientedImageForDisplay " + bounds.outWidth + "x" + bounds.outHeight
                + " sample=" + options.inSampleSize + " maxSide=" + maxSide);
        Bitmap bitmap = BitmapFactory.decodeFile(path, options);
        if (bitmap == null) {
            return null;
        }
        return applyExifOrientation(path, bitmap);
    }

    public static int calculateInSampleSize(int width, int height, int maxSide) {
        int sampleSize = 1;
        while ((width / sampleSize) > maxSide || (height / sampleSize) > maxSide) {
            sampleSize *= 2;
        }
        return Math.max(1, sampleSize);
    }

    private static Bitmap applyExifOrientation(String path, Bitmap bitmap) {
        try {
            ExifInterface exif = new ExifInterface(path);
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            Matrix matrix = new Matrix();
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) {
                matrix.setRotate(90);
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) {
                matrix.setRotate(180);
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) {
                matrix.setRotate(270);
            } else {
                return bitmap;
            }
            Bitmap orientedBitmap = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (orientedBitmap != bitmap) {
                bitmap.recycle();
            }
            return orientedBitmap;
        } catch (IOException | RuntimeException ignored) {
            return bitmap;
        }
    }

    public static int getVideoRotation(String path) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            return rotation == null ? 0 : Integer.parseInt(rotation);
        } catch (RuntimeException ignored) {
            return 0;
        } finally {
            try {
                retriever.release();
            } catch (IOException ignored) {
                // The rotation value has already been retrieved.
            }
        }
    }
}
