package com.barnabwhy.picozen;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;
import androidx.palette.graphics.Palette;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class AppsAdapter extends BaseAdapter
{
    private static Drawable iconDrawable;
    private static File iconFile;
    private static String packageName;
    private static long lastClickTime;
    private static MainActivity mainActivityContext;
    private final List<ApplicationInfo> appList;

    public enum SORT_FIELD { APP_NAME, RECENT_DATE, INSTALL_DATE }
    public enum SORT_ORDER { ASCENDING, DESCENDING }

    private static int itemScale;
    private final SettingsProvider settingsProvider;
    private GridView appGridView;

    public AppsAdapter(MainActivity context)
    {
        mainActivityContext = context;

        appGridView = mainActivityContext.findViewById(R.id.app_grid);
        itemScale = getPixelFromDip(200);
        appGridView.setColumnWidth(itemScale);
        settingsProvider = SettingsProvider.getInstance(mainActivityContext);

        appList = getInstalledApps();
        SORT_FIELD sortField = SORT_FIELD.values()[0];
        SORT_ORDER sortOrder = SORT_ORDER.values()[0];
        this.sort(sortField, sortOrder);
    }

    public static int getPixelFromDip(int dip) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dip, mainActivityContext.getResources().getDisplayMetrics());
    }

    private static class ViewHolder {
        RelativeLayout layout;
        ImageView imageView;
        TextView textView;
        ImageView progressBar;
    }

    public int getCount()
    {
        return appList.size();
    }

    public Object getItem(int position)
    {
        return appList.get(position);
    }

    public long getItemId(int position)
    {
        return position;
    }

    public ArrayList<ApplicationInfo> getInstalledApps() {
        final List<String> hiddenApps = Arrays.asList(mainActivityContext.getPackageName(), "com.android.traceur", "com.picovr.init.overlay", "com.picovr.provision", "com.pvr.appmanager", "com.pvr.seethrough.setting", "com.pvr.scenemanager");
        ArrayList<ApplicationInfo> installedApps = new ArrayList<>();
        PackageManager pm = mainActivityContext.getPackageManager();
        for (ApplicationInfo app : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            if (hiddenApps.contains(app.packageName))
                continue;

            if (!SettingsProvider.launchIntents.containsKey(app.packageName)) {
                SettingsProvider.launchIntents.put(app.packageName, pm.getLaunchIntentForPackage(app.packageName));
            }
            if(SettingsProvider.launchIntents.get(app.packageName) != null) {
                if(!SettingsProvider.installDates.containsKey(app.packageName)) {
                    long installDate;
                    try {
                        PackageInfo packageInfo = pm.getPackageInfo(app.packageName, 0);
                        installDate = packageInfo.firstInstallTime;
                    } catch (PackageManager.NameNotFoundException e) {
                        throw new RuntimeException(e);
                    }
                    SettingsProvider.installDates.put(app.packageName, installDate);
                }
                installedApps.add(app);
            }
        }
        return installedApps;
    }

    public String getAppDisplayName(String pkg, CharSequence label)
    {
        String name = mainActivityContext.getSharedPreferences(mainActivityContext.getPackageName() + "_preferences", Context.MODE_PRIVATE).getString(pkg, "");
        if (!name.isEmpty()) {
            return name;
        }

        String retVal = label.toString();
        if (retVal == null || retVal.equals("")) {
            retVal = pkg;
        }
        return retVal;
    }

    private final Handler handler = new Handler();
    @SuppressLint("NewApi")
    public View getView(int position, View convertView, ViewGroup parent)
    {
        ViewHolder holder;

        final ApplicationInfo currentApp = appList.get(position);
        LayoutInflater inflater = (LayoutInflater) mainActivityContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        if (convertView == null) {
            // Create a new ViewHolder and inflate the layout
            convertView = inflater.inflate(R.layout.lv_app, parent, false);
            holder = new ViewHolder();
            holder.layout = convertView.findViewById(R.id.layout);
            holder.imageView = convertView.findViewById(R.id.imageLabel);
            holder.textView = convertView.findViewById(R.id.textLabel);
            holder.progressBar = convertView.findViewById(R.id.progress_bar);
            convertView.setTag(holder);

            AtomicInteger textVisible = new AtomicInteger(View.INVISIBLE);
            convertView.setOnHoverListener((View view, MotionEvent event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_HOVER_ENTER) {
                    view.findViewById(R.id.app_icon_overlay).setVisibility(View.VISIBLE);
                    textVisible.set(view.findViewById(R.id.textLabel).getVisibility());
                    view.findViewById(R.id.textLabel).setVisibility(View.VISIBLE);

                } else if (event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) {
                    view.findViewById(R.id.app_icon_overlay).setVisibility(View.INVISIBLE);
                    view.findViewById(R.id.textLabel).setVisibility(textVisible.get());
                }
                return false;
            });

            // Set clipToOutline to true on imageView (Workaround for bug)
            holder.layout.setClipToOutline(true);

            ViewGroup.LayoutParams params = holder.layout.getLayoutParams();

            //Calculate text height
            holder.textView.measure(0, 0);
            int textHeight = (int) holder.textView.getMeasuredHeight() + (int) ((ViewGroup.MarginLayoutParams) holder.textView.getLayoutParams()).topMargin + (int) ((ViewGroup.MarginLayoutParams) holder.textView.getLayoutParams()).bottomMargin;

            params.width = itemScale;
//            if (style == 0) {
//                if(showTextLabels) {
//                    params.height = (int) ((itemScale) * 0.5625) + textHeight;
//                }else{
                    params.height = (int) ((itemScale) * 0.5625);
//                }
//            } else {
//                if(showTextLabels) {
//                    params.height = (int) (itemScale + textHeight);
//                }else{
//                    params.height = (int) itemScale;
//                }
//            }
            holder.layout.setLayoutParams(params);
        } else {
            // ViewHolder already exists, reuse it
            holder = (ViewHolder) convertView.getTag();
        }

        // set value into textview
        PackageManager pm = mainActivityContext.getPackageManager();
        String name = getAppDisplayName(currentApp.packageName, currentApp.loadLabel(pm));
        holder.textView.setText(name);

        holder.layout.setOnClickListener(view -> {
            holder.progressBar.setVisibility(View.VISIBLE);
            RotateAnimation rotateAnimation = new RotateAnimation(
                    0, 360,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f
            );
            rotateAnimation.setDuration(1000);
            rotateAnimation.setRepeatCount(Animation.INFINITE);
            rotateAnimation.setInterpolator(new LinearInterpolator());
            holder.progressBar.startAnimation(rotateAnimation);
            if(!mainActivityContext.openApp(currentApp)) {
                holder.progressBar.setVisibility(View.GONE);
                holder.progressBar.clearAnimation();
            }

            handler.removeCallbacksAndMessages(null);
            handler.postDelayed(() -> {
                AlphaAnimation fadeOut = new AlphaAnimation(1, 0);
                fadeOut.setDuration(1000);
                fadeOut.setFillAfter(true);
                holder.progressBar.startAnimation(fadeOut);
            }, 2000);
            handler.postDelayed(() -> {
                holder.progressBar.setVisibility(View.GONE);
                holder.progressBar.clearAnimation();
            }, 3000);
        });
//        holder.layout.setOnLongClickListener(view -> {
//            showAppDetails(currentApp);
//            return false;
//        });

        // set application icon
        try {
            loadIcon(mainActivityContext, holder, currentApp, name);
        } catch (Resources.NotFoundException e) {
            Log.e("loadIcon", "Error loading icon for app: " + currentApp.packageName, e);
        }
        return convertView;
    }

//    public void onImageSelected(String path, ImageView selectedImageView) {
//        clearIconCache();
//        if (path != null) {
//            Bitmap bitmap = ImageUtils.getResizedBitmap(BitmapFactory.decodeFile(path), 512);
//            ImageUtils.saveBitmap(bitmap, iconFile);
//            selectedImageView.setImageBitmap(bitmap);
//        } else {
//            selectedImageView.setImageDrawable(iconDrawable);
//            updateIcon(selectedImageView, iconFile, "banners."+ packageName);
//        }
//        mainActivityContext.reloadUI();
//        this.notifyDataSetChanged(); // for real time updates
//    }

    private Long getInstallDate(ApplicationInfo applicationInfo) {
        if(SettingsProvider.installDates.containsKey(applicationInfo.packageName)) {
            return SettingsProvider.installDates.get(applicationInfo.packageName);
        }else{
            return 0L;
        }
    }

    public void sort(SORT_FIELD field, SORT_ORDER order) {
        final PackageManager pm = mainActivityContext.getPackageManager();
        final Map<String, Long> recents = settingsProvider.getRecents();

        Collections.sort(appList, (a, b) -> {
            String na;
            String nb;
            long naL;
            long nbL;
            int result;
            switch (field) {
                case RECENT_DATE:
                    if (recents.containsKey(a.packageName)) {
                        naL = recents.get(a.packageName);
                    } else {
                        naL = getInstallDate(a);
                    }
                    if (recents.containsKey(b.packageName)) {
                        nbL = recents.get(b.packageName);
                    } else {
                        nbL = getInstallDate(b);
                    }
                    result = Long.compare(naL, nbL);
                    break;

                case INSTALL_DATE:
                    naL = getInstallDate(a);
                    nbL = getInstallDate(b);
                    result = Long.compare(naL, nbL);
                    break;

                default: //by APP_NAME
                    na = SettingsProvider.getAppDisplayName(mainActivityContext, a.packageName, a.loadLabel(pm)).toUpperCase();
                    nb = SettingsProvider.getAppDisplayName(mainActivityContext, b.packageName, b.loadLabel(pm)).toUpperCase();
                    result = na.compareTo(nb);
                    break;
            }

            return order == SORT_ORDER.ASCENDING ? result : -result;
        });
        this.notifyDataSetChanged();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
//    private void showAppDetails(ApplicationInfo actApp) {
//
//        //set layout
//        Context context = mainActivityContext;
//        AlertDialog.Builder builder = new AlertDialog.Builder(context, R.style.CustomDialog);
//        builder.setView(R.layout.dialog_app_details);
//        AlertDialog dialog = builder.create();
//        dialog.getWindow().setBackgroundDrawableResource(R.drawable.bkg_dialog);
//        dialog.show();
//
//        mainActivityContext.findViewById(R.id.dialogDim).setVisibility(View.VISIBLE);
//        dialog.setOnDismissListener((DialogInterface dialogInterface) -> {
//            mainActivityContext.findViewById(R.id.dialogDim).setVisibility(View.GONE);
//        });
//
//        //info action
//        dialog.findViewById(R.id.info).setOnClickListener(view13 -> mainActivityContext.openAppDetails(actApp.packageName));
//
//        //set name
//        PackageManager pm = mainActivityContext.getPackageManager();
//        String name = SettingsProvider.getAppDisplayName(mainActivityContext, actApp.packageName, actApp.loadLabel(pm));
//        final EditText input = dialog.findViewById(R.id.app_name);
//        input.setText(name);
//        dialog.findViewById(R.id.ok).setOnClickListener(view12 -> {
//            settingsProvider.setAppDisplayName(actApp, input.getText().toString());
//            mainActivityContext.reloadUI();
//            dialog.dismiss();
//        });
//
//        // load icon
//        ImageView tempImage = dialog.findViewById(R.id.app_icon);
//        AbstractPlatform platform = AbstractPlatform.getPlatform(actApp);
//        platform.loadIcon(mainActivityContext, tempImage, actApp, name);
//
//        tempImage.setClipToOutline(true);
//
//        tempImage.setOnClickListener(view1 -> {
//            iconDrawable = actApp.loadIcon(pm);
//            packageName = actApp.packageName;
//            iconFile = AbstractPlatform.pkg2path(mainActivityContext, STYLES[style]+"."+actApp.packageName);
//            if (iconFile.exists()) {
//                iconFile.delete();
//            }
//            mainActivityContext.setSelectedImageView(tempImage);
//            ImageUtils.showImagePicker(mainActivityContext, MainActivity.PICK_ICON_CODE);
//        });
//    }

    public String getSelectedPackage() {
        return packageName;
    }

    public static File pkg2path(Context context, String pkg) {
        return new File(context.getCacheDir(), pkg + ".webp");
    }

    private final String ICONS1_URL = "https://raw.githubusercontent.com/Veticia/binaries/main/banners/";
    private static final String ICONS_FALLBACK_URL = "https://pilauncher.lwiczka.pl/get_icon.php?id=";
    protected static final HashMap<String, Drawable> iconCache = new HashMap<>();
    protected static final HashSet<String> ignoredIcons = new HashSet<>();

    private void loadIcon(Activity activity, ViewHolder holder, ApplicationInfo app, String name) {
        String pkg = app.packageName;

        if (iconCache.containsKey("banners."+pkg)) {
            holder.imageView.setImageDrawable(iconCache.get("banners."+pkg));
            setAppIconType(holder, true);
            return;
        }else{
            setAppIconType(holder, false);
            PackageManager pm = activity.getPackageManager();
            Resources resources;
            try {
                resources = pm.getResourcesForApplication(app.packageName);
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
                return;
            }
            int iconId = app.icon;
            if (iconId == 0) {
                iconId = android.R.drawable.sym_def_app_icon;
            }
            Drawable appIcon = ResourcesCompat.getDrawableForDensity(resources, iconId, DisplayMetrics.DENSITY_XXXHIGH, null);
            holder.imageView.setImageDrawable(appIcon);

            Bitmap icon = drawableToBitmap(appIcon);
            createIconPaletteAsync(icon, holder);
        }

        final File file = pkg2path(activity, "banners."+pkg);
        if (file.exists()) {
            Log.i("Exists", file.getAbsolutePath());
            if (updateIcon(holder, file, "banners."+pkg)) {
                return;
            }
        }
        downloadIcon(activity, pkg, name, () -> { updateIcon(holder, file, "banners."+pkg); });
    }

    public static boolean isImageFileComplete(File imageFile) {
        boolean success = false;
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath());
            success = (bitmap != null);
        } catch (Exception e) {
            // do nothing
        }
        if (!success) {
            Log.e("imgComplete", "Failed to read image file: " + imageFile);
        }
        return success;
    }

    public static void clearIconCache() {
        ignoredIcons.clear();
        iconCache.clear();
    }

    public static void clearAllIcons(MainActivity activity) {
        for (String pkg : iconCache.keySet()) {
            final File file = pkg2path(activity, pkg);
            Log.i("Cache file", file.getAbsolutePath() + " | Exists: " + file.exists());
            if (file.exists()) {
                file.delete();
            }
        }
        clearIconCache();
    }

    public static void setAppIconType(ViewHolder holder, boolean isBanner) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) holder.imageView.getLayoutParams();
        if(isBanner) {
            params.height = (int)(itemScale * 0.5625);
            params.rightMargin = 0;
            params.topMargin = 0;
            params.bottomMargin = 0;
            holder.imageView.setLayoutParams(params);
            holder.imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            holder.textView.setVisibility(View.INVISIBLE);
        } else {
            params.height = getPixelFromDip(40);
            params.rightMargin = getPixelFromDip(15);
            params.bottomMargin = getPixelFromDip(15);
            params.topMargin = (int)(itemScale * 0.5625 - params.height - params.bottomMargin);
            holder.imageView.setLayoutParams(params);
            holder.imageView.setScaleType(ImageView.ScaleType.FIT_END);
            holder.textView.setVisibility(View.VISIBLE);
        }
    }

    public static boolean updateIcon(ViewHolder holder, File file, String pkg) {
        try {
            Drawable drawable = Drawable.createFromPath(file.getAbsolutePath());
            if (drawable != null) {
                holder.imageView.setImageDrawable(drawable);
                iconCache.put(pkg, drawable);
                setAppIconType(holder, true);

                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private void downloadIcon(final Activity activity, String pkg, @SuppressWarnings("unused") String name, final Runnable callback) {
        final File file = pkg2path(activity, "banners." + pkg);
        new Thread(() -> {
            try {
                synchronized (pkg) {
                    //if (ignoredIcons.contains(STYLES[style] + "." + file.getName())) {
                    //ignored icon
                    //} else
                    if(downloadIconFromStore(pkg, file, "us", 0)) {
                        activity.runOnUiThread(callback);
                    } else if(downloadIconFromStore(pkg, file, "cn", 0)) {
                        activity.runOnUiThread(callback);
                    } else if (downloadIconFromUrl(ICONS1_URL + pkg + ".png", file)) {
                        activity.runOnUiThread(callback);
                    //} else if (downloadIconFromUrl(ICONS_FALLBACK_URL + pkg + "&set=banners", file)) {
                    //    activity.runOnUiThread(callback);
                    } else {
                        Log.d("Missing icon", file.getName());
                        ignoredIcons.add("banners." + file.getName());
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    protected static boolean downloadIconFromUrl(String url, File outputFile) {
        try {
            return saveStream(new URL(url).openStream(), outputFile);
        } catch (Exception e) {
            return false;
        }
    }

    protected static boolean saveStream(InputStream is, File outputFile) {
        try {
            DataInputStream dis = new DataInputStream(is);

            int length;
            byte[] buffer = new byte[65536];
            FileOutputStream fos = new FileOutputStream(outputFile);
            while ((length = dis.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
            fos.flush();
            fos.close();

            if (!isImageFileComplete(outputFile)) {
                return false;
            }

            Bitmap bitmap = BitmapFactory.decodeFile(outputFile.getAbsolutePath());
            if (bitmap != null) {
                int width = bitmap.getWidth();
                int height = bitmap.getHeight();
                float aspectRatio = (float) width / height;
                if (width > 512) {
                    width = 512;
                    height = Math.round(width / aspectRatio);
                    bitmap = Bitmap.createScaledBitmap(bitmap, width, height, false);
                }
                try {
                    fos = new FileOutputStream(outputFile);
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 75, fos);
                    fos.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    protected static boolean downloadIconFromStore(String pkg, File outputFile, String region, int style) {
        if(isPicoHeadset()) {
            try {
                URL url = new URL("https://appstore-"+region+".picovr.com/api/app/v1/item/info?app_language=en&device_name=A8110&manifest_version_code=300800000&package_name=" + pkg);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.connect();

                InputStream stream = connection.getInputStream();

                BufferedReader reader = new BufferedReader(new InputStreamReader(stream));

                StringBuilder str = new StringBuilder();
                String line = "";

                while ((line = reader.readLine()) != null) {
                    str.append(line).append("\n");
                }

                JSONObject result = new JSONObject(str.toString());

                if(result.isNull("data") || result.getJSONObject("data").isNull("cover")) {
                    return false;
                }

                String imageKey = style == 0 ? "landscape" : "square";
                return downloadIconFromUrl(result.getJSONObject("data").getJSONObject("cover").getString(imageKey), outputFile);
            } catch (Exception e) {
                return false;
            }
        } else if(isOculusHeadset()) {
            return false;
        } else {
            return false;
        }
    }

    public static boolean isMagicLeapHeadset() {
        String vendor = Build.MANUFACTURER.toUpperCase();
        return vendor.startsWith("MAGIC LEAP");
    }

    public static boolean isOculusHeadset() {
        String vendor = Build.MANUFACTURER.toUpperCase();
        return vendor.startsWith("META") || vendor.startsWith("OCULUS");
    }

    public static boolean isPicoHeadset() {
        String vendor = Build.MANUFACTURER.toUpperCase();
        return vendor.startsWith("PICO") || vendor.startsWith("PİCO");
    }

    // Generate palette asynchronously and use it on a different
    // thread using onGenerated()
    public static void createIconPaletteAsync(Bitmap bitmap, ViewHolder holder) {
        Palette.from(bitmap).generate(new Palette.PaletteAsyncListener() {
            public void onGenerated(Palette p) {
                // Use generated instance
                GradientDrawable gd = new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[] { p.getVibrantColor(mainActivityContext.getResources().getColor(R.color.bg_med)), p.getDarkVibrantColor(mainActivityContext.getResources().getColor(R.color.bg_dark)) });
                gd.setCornerRadius(0f);
                holder.layout.findViewById(R.id.app_color_bg).setBackground(gd);
            }
        });
    }

    public static Bitmap drawableToBitmap(Drawable drawable) {
        Bitmap bitmap = null;

        if (drawable instanceof BitmapDrawable) {
            BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
            if(bitmapDrawable.getBitmap() != null) {
                return bitmapDrawable.getBitmap();
            }
        }

        if(drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) {
            bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); // Single color bitmap will be created of 1x1 pixel
        } else {
            bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        }

        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }
}
