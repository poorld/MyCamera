package com.android.mycamera.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;

import java.io.IOException;

public final class MediaOrientationUtils {

    private MediaOrientationUtils() {
    }

    public static Bitmap decodeOrientedImage(String path, BitmapFactory.Options options) {
        Bitmap bitmap = BitmapFactory.decodeFile(path, options);
        if (bitmap == null) return null;

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
            Bitmap orientedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
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
