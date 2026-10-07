package com.aus.notelikeus;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

public class TestImageContentProvider extends ContentProvider {

    public static final String AUTHORITY = "com.aus.notelikeus.test.provider";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/test_share_image.png");
    public static final Uri CONTENT_URI_NULL_MIME = Uri.parse("content://" + AUTHORITY + "/null_mime");
    public static final Uri CONTENT_URI_NON_IMAGE_MIME = Uri.parse("content://" + AUTHORITY + "/non_image_mime");
    public static final Uri CONTENT_URI_DELAYED = Uri.parse("content://" + AUTHORITY + "/delayed");
    public static final Uri CONTENT_URI_SECURITY_EXCEPTION = Uri.parse("content://" + AUTHORITY + "/security_exception");
    public static final Uri CONTENT_URI_ZERO_BYTES = Uri.parse("content://" + AUTHORITY + "/zero_bytes");
    public static final Uri CONTENT_URI_OVERSIZED = Uri.parse("content://" + AUTHORITY + "/oversized");

    public static java.util.concurrent.CountDownLatch delayLatch = null;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        if (uri.equals(CONTENT_URI_NULL_MIME)) return null;
        if (uri.equals(CONTENT_URI_NON_IMAGE_MIME)) return "application/pdf";
        return "image/png";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (uri.equals(CONTENT_URI_SECURITY_EXCEPTION)) {
            throw new SecurityException("Simulated security exception");
        }

        if (uri.equals(CONTENT_URI_DELAYED) && delayLatch != null) {
            try {
                delayLatch.await();
            } catch (InterruptedException e) {
                // ignore
            }
        }

        Context ctx = getContext();
        if (ctx == null) {
            throw new FileNotFoundException("No context available");
        }
        File file = new File(ctx.getCacheDir(), "test_share_" + uri.getLastPathSegment() + ".tmp");
        
        try (FileOutputStream out = new FileOutputStream(file)) {
            if (uri.equals(CONTENT_URI_ZERO_BYTES)) {
                // write nothing
            } else if (uri.equals(CONTENT_URI_OVERSIZED)) {
                byte[] chunk = new byte[1024 * 1024]; // 1MB chunk
                for(int i=0; i<30; i++) { // 30MB
                    out.write(chunk);
                }
            } else {
                out.write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to write test file: " + e.getMessage());
        }
        
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
