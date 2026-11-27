package local.jiege.hook.gallery;

import android.content.Context;
import android.database.Cursor;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.widget.TextView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.PhotoRecipe;
import local.jiege.hook.common.Settings;
import local.jiege.settings.ModuleSettings;

/**
 * 相册照片详情页原生展示拍摄配方：
 * 监听详情页文件名控件 (tv_full_name) 更新，异步从 EXIF UserComment 或文件尾无损标记读取 OmniCam 配方，
 * 并在 tv_full_name 第二行以原生副标题样式展示模式、色彩引擎、滤镜与柔光信息。
 */
public final class GalleryRecipeDetails {
    private static final String TAG = "GalleryRecipeDetails";
    private static final int TAG_KEY_FILE = 0x6A670101;
    private static final ThreadLocal<Boolean> UPDATING = new ThreadLocal<>();

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, TAG);
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static int fullNameId;

    private GalleryRecipeDetails() {}

    public static void installGallery(ClassLoader classLoader) {
        Log.guard(TAG, "photo details recipe row", () -> XposedHelpers.findAndHookMethod(TextView.class, "setText",
            CharSequence.class, TextView.BufferType.class, boolean.class, int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(UPDATING.get())) return;
                    TextView view = (TextView) param.thisObject;
                    if (view.getId() == View.NO_ID) return;
                    if (fullNameId == 0) {
                        fullNameId = view.getResources().getIdentifier("tv_full_name", "id", view.getContext().getPackageName());
                    }
                    if (view.getId() != fullNameId) return;
                    CharSequence text = view.getText();
                    String raw = text == null ? "" : text.toString();
                    int nl = raw.indexOf('\n');
                    String fileName = (nl >= 0 ? raw.substring(0, nl) : raw).trim();
                    onFileNameUpdated(view, fileName);
                }
            }));
    }

    private static void onFileNameUpdated(TextView nameView, String fileName) {
        if (!Settings.get(ModuleSettings.PHOTO_RECIPE_EXIF) || fileName.isEmpty()) {
            return;
        }
        nameView.setTag(TAG_KEY_FILE, fileName);
        Context appContext = nameView.getContext().getApplicationContext();
        EXECUTOR.execute(() -> {
            PhotoRecipe recipe = readRecipe(appContext, fileName);
            String display = recipe == null ? "" : recipe.formatDisplay();
            if (display.isEmpty()) return;
            MAIN.post(() -> {
                Object latest = nameView.getTag(TAG_KEY_FILE);
                if (!fileName.equals(latest)) return;
                applyRecipeLine(nameView, fileName, display);
            });
        });
    }

    private static void applyRecipeLine(TextView nameView, String fileName, String display) {
        SpannableStringBuilder ssb = new SpannableStringBuilder(fileName);
        int start = ssb.length();
        ssb.append('\n').append(display);
        int color = nameView.getCurrentTextColor();
        int muted = (color & 0x00FFFFFF) | (0xB8 << 24);
        ssb.setSpan(new RelativeSizeSpan(0.88f), start + 1, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.setSpan(new ForegroundColorSpan(muted), start + 1, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        UPDATING.set(Boolean.TRUE);
        try {
            nameView.setSingleLine(false);
            nameView.setMaxLines(3);
            nameView.setText(ssb);
        } finally {
            UPDATING.remove();
        }
    }

    /** 优先读 EXIF UserComment，其次读文件末尾 512 字节的无损配方标记。 */
    private static PhotoRecipe readRecipe(Context context, String name) {
        Uri files = MediaStore.Files.getContentUri("external");
        try (Cursor cursor = context.getContentResolver().query(
                files,
                new String[] {MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DISPLAY_NAME + " = ? COLLATE NOCASE",
                new String[] {name},
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            while (cursor != null && cursor.moveToNext()) {
                Uri item = MediaStore.setRequireOriginal(Uri.withAppendedPath(files, String.valueOf(cursor.getLong(0))));
                // 1. 先尝试从文件尾快速读取（覆盖 native 直出 JPEG）
                PhotoRecipe tail = readTailRecipe(context, item);
                if (tail != null) return tail;
                // 2. 再尝试从 EXIF UserComment 读取（覆盖 OplusExifInterface 路径）
                try (InputStream in = context.getContentResolver().openInputStream(item)) {
                    if (in == null) continue;
                    String comment = new ExifInterface(in).getAttribute(ExifInterface.TAG_USER_COMMENT);
                    PhotoRecipe recipe = PhotoRecipe.parse(comment);
                    if (recipe != null) return recipe;
                } catch (Exception error) {
                    Log.e(TAG, "EXIF of " + name + " unreadable", error);
                }
            }
        } catch (Exception error) {
            Log.e(TAG, "media lookup of " + name + " failed", error);
        }
        return null;
    }

    private static PhotoRecipe readTailRecipe(Context context, Uri uri) {
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) return null;
            long size = pfd.getStatSize();
            if (size <= 0) return null;
            int readLen = (int) Math.min(512L, size);
            try (FileInputStream fis = new FileInputStream(pfd.getFileDescriptor())) {
                fis.getChannel().position(size - readLen);
                byte[] buf = new byte[readLen];
                int n = fis.read(buf);
                if (n <= 0) return null;
                String tail = new String(buf, 0, n, StandardCharsets.UTF_8);
                return PhotoRecipe.parse(tail);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }
}
