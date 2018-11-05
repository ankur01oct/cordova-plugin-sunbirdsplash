package org.sunbird;

import android.Manifest;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.ekstep.genieservices.GenieService;
import org.ekstep.genieservices.async.GenieAsyncService;
import org.ekstep.genieservices.commons.IResponseHandler;
import org.ekstep.genieservices.commons.bean.Content;
import org.ekstep.genieservices.commons.bean.ContentDetailsRequest;
import org.ekstep.genieservices.commons.bean.GenieResponse;
import org.ekstep.genieservices.commons.bean.ImportContentProgress;
import org.ekstep.genieservices.commons.bean.enums.ContentImportStatus;
import org.ekstep.genieservices.commons.bean.telemetry.Impression;
import org.ekstep.genieservices.commons.utils.GsonUtil;
import org.ekstep.genieservices.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.sunbird.deeplinks.DeepLinkNavigation;
import org.sunbird.locales.Locale;
import org.sunbird.util.ImportExportUtil;

import java.util.ArrayList;

public class SplashScreen extends CordovaPlugin {

  private static final String TAG = "SplashScreen";

  private static final String LOG_TAG = "SplashScreen";
  private static final String KEY_LOGO = "app_logo";
  private static final String KEY_NAME = "app_name";
  private static final String KEY_IS_FIRST_TIME = "is_first_time";

  private static final int DEFAULT_SPLASHSCREEN_DURATION = 3000;
  private static final int DEFAULT_FADE_DURATION = 500;

  private static final int IMPORT_SUCCESS = 1;
  private static final int IMPORT_ERROR = 2;
  private static final int IMPORT_PROGRESS = 3;
  private static final int IMPORTING_COUNT = 4;
  private static final int IMPORT_FAILED = 5;
  private static final int NOT_COMPATIBLE = 6;
  private static final int CONTENT_EXPIRED = 7;
  private static final int ALREADY_EXIST = 8;

  private static Dialog splashDialog;
  private ImageView splashImageView;
  private TextView importStatusTextView;
  private int orientation;
  private SharedPreferences sharedPreferences;
  private volatile boolean importingInProgress;
  private DeepLinkNavigation mDeepLinkNavigation;
  private ArrayList<CallbackContext> mHandler = new ArrayList<>();
  private JSONObject mLastEvent;
  private String localeSelected;
  private Intent deepLinkIntent;

  private static int getIdOfResource(CordovaInterface cordova, String name, String resourceType) {
    return cordova.getActivity().getResources().getIdentifier(name, resourceType,
      cordova.getActivity().getApplicationInfo().packageName);
  }




  // Helper to be compile-time compatible with both Cordova 3.x and 4.x.
  private View getView() {
    try {
      return (View) webView.getClass().getMethod("getView").invoke(webView);
    } catch (Exception e) {
      return (View) webView;
    }
  }

  private int getSplashId() {
    int drawableId = 0;
    String splashResource = "screen";
    drawableId = cordova.getActivity().getResources().getIdentifier(splashResource, "drawable",
      cordova.getActivity().getClass().getPackage().getName());
    if (drawableId == 0) {
      drawableId = cordova.getActivity().getResources().getIdentifier(splashResource, "drawable",
        cordova.getActivity().getPackageName());
    }
    return drawableId;
  }

  @Override
  protected void pluginInitialize() {
    sharedPreferences = cordova.getActivity().getSharedPreferences("SUNBIRD_SPLASH", Context.MODE_PRIVATE);
    //use of shared preference here ?
    cordova.getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        getView().setVisibility(View.INVISIBLE);
      }
    });
    // Save initial orientation.
    orientation = cordova.getActivity().getResources().getConfiguration().orientation;
    displaySplashScreen();
  }

  private int getFadeDuration() {
    return DEFAULT_FADE_DURATION;
  }

  @Override
  public void onStart() {
    super.onStart();
    if (!EventBus.isSubscriberRegistered(this)) {
      EventBus.registerSubscriber(this);
    }
  }

  @Override
  public void onPause(boolean multitasking) {
    this.hideSplashScreen(true);
  }

  @Override
  public void onStop() {
    super.onStop();
    EventBus.unregisterSubscriber(this);
  }

  @Override
  public void onDestroy() {
    this.hideSplashScreen(true);
  }

  @Override
  public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
    if (action.equals("hide")) {
      if (!importingInProgress) {
        cordova.getActivity().runOnUiThread(new Runnable() {
          public void run() {
            webView.postMessage("splashscreen", "hide");
          }
        });
      }
    } else if (action.equals("show")) {
      cordova.getActivity().runOnUiThread(new Runnable() {
        public void run() {
          webView.postMessage("splashscreen", "show");
        }
      });
    } else if (action.equals("setContent")) {
      String appName = args.getString(0);
      String logoUrl = args.getString(1);
      cacheImageAndAppName(appName, logoUrl);
    } else if (action.equals("onDeepLink")) {
      mHandler.add(callbackContext);
      consumeEvents();
    } else if (action.equals("clearPrefs")) {
      if (sharedPreferences != null) {
        sharedPreferences.edit().clear().apply();
        callbackContext.success();
      }
    } else {
      return false;
    }

    // callbackContext.success();
    return true;
  }

  private void cacheImageAndAppName(String appName, String logoUrl) {
    int dim = getSplashDim(cordova.getActivity().getWindowManager().getDefaultDisplay());
    sharedPreferences.edit().putString(KEY_NAME, appName).putString(KEY_LOGO, logoUrl).apply();
    Glide.with(cordova.getActivity()).load(logoUrl).downloadOnly(dim, dim);
  }

  @Override
  public Object onMessage(String id, Object data) {
    if ("splashscreen".equals(id)) {
      if ("hide".equals(data.toString())) {
        hide();
      } else if ("show".equals(data.toString())) {
        this.displaySplashScreen();
      }
    }
    return null;
  }

  private void hide() {

    // To avoid black screen while content importing
    if (importingInProgress) {
      return;
    }

    this.hideSplashScreen(false);
    getView().setVisibility(View.VISIBLE);
  }

  // Don't add @Override so that plugin still compiles on 3.x.x for a while
  public void onConfigurationChanged(Configuration newConfig) {
    if (newConfig.orientation != orientation) {
      orientation = newConfig.orientation;

      // Splash drawable may change with orientation, so reload it.
      if (splashImageView != null) {
        int drawableId = getSplashId();
        if (drawableId != 0) {
          splashImageView.setImageDrawable(cordova.getActivity().getResources().getDrawable(drawableId));
        }
      }
    }
  }

  private void hideSplashScreen(final boolean forceHideImmediately) {
    // To avoid black screen while content importing
    if (importingInProgress) {
      return;
    }

    cordova.getActivity().runOnUiThread(new Runnable() {
      public void run() {
        if (splashDialog != null) {
          splashDialog.dismiss();
          splashDialog = null;
          splashImageView = null;
        }

      }
    });
  }

  private void generateTelemetry() {
    Impression impression = new Impression.Builder().type("view").pageId("splash").environment("home").build();
    boolean isFirstTime = sharedPreferences.getBoolean(KEY_IS_FIRST_TIME, true);
    if (isFirstTime) {
      sharedPreferences.edit().putBoolean(KEY_IS_FIRST_TIME, false).apply();
    }
    org.ekstep.genieservices.commons.bean.telemetry.Log log = new org.ekstep.genieservices.commons.bean.telemetry.Log.Builder()
      .environment("home")
      .type("view")
      .level(org.ekstep.genieservices.commons.bean.telemetry.Log.Level.INFO)
      .message("splash")
      .addParam("isFirstTime", isFirstTime)
      .build();

    GenieAsyncService genieAsyncService = GenieService.getAsyncService();

    if (genieAsyncService != null && genieAsyncService.getTelemetryService() != null) {

      IResponseHandler<Void> iResponseHandler = new IResponseHandler<Void>() {
        @Override
        public void onSuccess(GenieResponse<Void> genieResponse) {

        }

        @Override
        public void onError(GenieResponse<Void> genieResponse) {

        }
      };
      genieAsyncService.getTelemetryService().saveTelemetry(impression, new IResponseHandler<Void>() {
        @Override
        public void onSuccess(GenieResponse<Void> genieResponse) {
          genieAsyncService.getTelemetryService().saveTelemetry(log, iResponseHandler);
        }

        @Override
        public void onError(GenieResponse<Void> genieResponse) {

        }
      });

    }
  }

  /**
   * Shows the splash screen over the full Activity
   */
  @SuppressWarnings("deprecation")
  private void displaySplashScreen() {
    generateTelemetry();
    final int splashscreenTime = DEFAULT_SPLASHSCREEN_DURATION;
    final int drawableId = getSplashId();

    final String appName = sharedPreferences.getString(KEY_NAME,
      cordova.getActivity().getString(getIdOfResource(cordova, "_app_name", "string")));
    final String logoUrl = sharedPreferences.getString(KEY_LOGO, "");

    final int fadeSplashScreenDuration = getFadeDuration();
    final int effectiveSplashDuration = Math.max(0, splashscreenTime - fadeSplashScreenDuration);

    // Prevent to show the splash dialog if the activity is in the process of
    // finishing
    if (cordova.getActivity().isFinishing()) {
      return;
    }
    // If the splash dialog is showing don't try to show it again
    if (splashDialog != null && splashDialog.isShowing()) {
      return;
    }

    cordova.getActivity().runOnUiThread(new Runnable() {
      public void run() {
        // Get reference to display
        Display display = cordova.getActivity().getWindowManager().getDefaultDisplay();
        Context context = webView.getContext();
        int splashDim = getSplashDim(display);

        LinearLayout splashContent = createParentContentView(context);

        createLogoImageView(context, splashDim, drawableId, logoUrl);
        createImportStatusView(context);
        TextView appNameTextView = createAppNameView(context, appName);

        splashContent.addView(splashImageView);
        splashContent.addView(appNameTextView);
        splashContent.addView(importStatusTextView);

        // Create and show the dialog
        splashDialog = new Dialog(context, android.R.style.Theme_Translucent_NoTitleBar);
        // check to see if the splash screen should be full screen
        if ((cordova.getActivity().getWindow().getAttributes().flags
          & WindowManager.LayoutParams.FLAG_FULLSCREEN) == WindowManager.LayoutParams.FLAG_FULLSCREEN) {
          splashDialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        splashDialog.setContentView(splashContent);
        splashDialog.setCancelable(false);
        splashDialog.show();

      }
    });
  }

  private int getSplashDim(Display display) {
    return display.getWidth() < display.getHeight() ? display.getWidth() : display.getHeight();
  }

  @NonNull
  private TextView createAppNameView(Context context, String appName) {
    TextView appNameTextView = new TextView(context);
    LinearLayout.LayoutParams textViewParam = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
      LayoutParams.WRAP_CONTENT);
    textViewParam.setMargins(10, 10, 10, 10);
    appNameTextView.setText(appName);
    appNameTextView.setTextSize(20);
    appNameTextView.setTextColor(Color.GRAY);
    appNameTextView.setGravity(Gravity.CENTER_HORIZONTAL);
    appNameTextView.setLayoutParams(textViewParam);

    setTypeFace(context, appNameTextView);
    return appNameTextView;
  }

  private void setTypeFace(Context context, TextView textView) {
    try {
      String NOTO_COMBINED = "www/sunbird/assets/fonts/natosans/" + "NotoSans-Regular.ttf";
      Typeface tf = Typeface.createFromAsset(context.getAssets(), NOTO_COMBINED);
      textView.setTypeface(tf);
    }
    catch (Exception exception){
      System.out.println(exception);
    }
  }

  private void createLogoImageView(Context context, int splashDim, int drawableId, String logoUrl) {
    splashImageView = new ImageView(context);
    // splashImageView.setImageResource(drawableId);
    LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(splashDim, splashDim);
    layoutParams.setMargins(10, splashDim / 4, 10, 0);
    splashImageView.setLayoutParams(layoutParams);

    splashImageView.setMinimumHeight(splashDim);
    splashImageView.setMinimumWidth(splashDim);

    // TODO: Use the background color of the webView's parent instead of using the
    // preference.

    splashImageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

    if (TextUtils.isEmpty(logoUrl)) {
      splashImageView.setImageResource(drawableId);
    } else {
      Glide.with(context).load(logoUrl).asBitmap().diskCacheStrategy(DiskCacheStrategy.ALL)
        .placeholder(drawableId).into(splashImageView);
    }

  }

  @NonNull
  private LinearLayout createParentContentView(Context context) {
    LinearLayout splashContent = new LinearLayout(context);
    splashContent.setOrientation(LinearLayout.VERTICAL);
    splashContent.setBackgroundColor(Color.WHITE);
    LayoutParams parentParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
    splashContent.setLayoutParams(parentParams);
    return splashContent;
  }

  private void consumeEvents() {
    if (this.mHandler.size() == 0 || mLastEvent == null) {
      return;
    }

    for (CallbackContext callback : this.mHandler) {
      final PluginResult result = new PluginResult(PluginResult.Status.OK, mLastEvent);
      result.setKeepCallback(true);
      callback.sendPluginResult(result);
    }

    mLastEvent = null;
  }


}
