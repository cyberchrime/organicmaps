package app.organicmaps.routing;

import static android.Manifest.permission.POST_NOTIFICATIONS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import app.organicmaps.Framework;
import app.organicmaps.MwmActivity;
import app.organicmaps.R;
import app.organicmaps.location.LocationHelper;
import app.organicmaps.location.LocationListener;
import app.organicmaps.sound.TtsPlayer;
import app.organicmaps.util.log.Logger;

public class NavigationService extends Service implements LocationListener
{
  private static final String TAG = NavigationService.class.getSimpleName();

  private static final String CHANNEL_ID = "LOCATION_CHANNEL";
  private static final int NOTIFICATION_ID = 12345678;

  @SuppressWarnings("NotNullFieldNotInitialized")
  @NonNull
  private RemoteViews mRemoteViews;

  @SuppressWarnings("NotNullFieldNotInitialized")
  @NonNull
  private NotificationManagerCompat mNotificationManager;

  @SuppressWarnings("NotNullFieldNotInitialized")
  @NonNull
  private NotificationCompat.Builder mNotificationBuilder;

  public static void start(@NonNull Context context)
  {
    Logger.i("SERVICE", "start first time");

    ContextCompat.startForegroundService(context, new Intent(context, NavigationService.class));

    Logger.i("SERVICE", "start second time");
    ContextCompat.startForegroundService(context, new Intent(context, NavigationService.class));
  }

  public static void stop(@NonNull Context context)
  {
    context.stopService(new Intent(context, NavigationService.class));
  }

  @Override
  public void onCreate()
  {
    Logger.i(TAG);

    /*
     * Create notification channel.
     */
    mNotificationManager = NotificationManagerCompat.from(this);
    final NotificationChannelCompat channel = new NotificationChannelCompat.Builder(CHANNEL_ID,
      NotificationManagerCompat.IMPORTANCE_LOW)
        .setName(getString(R.string.prefs_group_route))
        .setLightsEnabled(false)    // less annoying
        .setVibrationEnabled(false) // less annoying
        .build();
    mNotificationManager.createNotificationChannel(channel);


    /*
     * Create notification builder.
     */
    mRemoteViews = new RemoteViews(getPackageName(), R.layout.layout_nav_notification);

    final int FLAG_IMMUTABLE = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ? 0 : PendingIntent.FLAG_IMMUTABLE;
    final Intent contentIntent = new Intent(this, MwmActivity.class);
    final PendingIntent contentPendingIntent = PendingIntent.getActivity(this, 0, contentIntent,
        PendingIntent.FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE);

    mNotificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
        .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
        .setPriority(Notification.PRIORITY_LOW)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setOngoing(true)
        .setShowWhen(false)
        .setOnlyAlertOnce(true)
        .setSmallIcon(R.drawable.ic_notification)
        .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
        .setCustomContentView(mRemoteViews)
        .setCustomBigContentView(mRemoteViews)
        .setContentIntent(contentPendingIntent);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
    {
      mNotificationBuilder = mNotificationBuilder
          .setColorized(true)
          .setColor(ContextCompat.getColor(this, R.color.bg_navigation_notification));
    }

    /*
     * Subscribe to location updates.
     */
    LocationHelper.INSTANCE.addListener(this);
  }

  @Override
  public void onDestroy()
  {
    Logger.i(TAG);
    super.onDestroy();
    LocationHelper.INSTANCE.removeListener(this);
    TtsPlayer.INSTANCE.stop();

    mNotificationManager.cancelAll();
    mNotificationManager.deleteNotificationChannel(CHANNEL_ID);
  }

  @Override
  public void onLowMemory()
  {
    super.onLowMemory();
    Logger.d(TAG, "onLowMemory()");
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId)
  {
    Logger.i(TAG);

    Logger.i(TAG, "Starting foreground");
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
    {
      try
      {
        startForeground(NavigationService.NOTIFICATION_ID, mNotificationBuilder.build());
      }
      catch (ForegroundServiceStartNotAllowedException e)
      {
        Logger.e(TAG, "Oops! ForegroundService is not allowed", e);
      }
    }
    else
    {
      startForeground(NavigationService.NOTIFICATION_ID, mNotificationBuilder.build());
    }

    return START_STICKY;
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent)
  {
    Logger.i(TAG);
    return null;
  }

  @Override
  public void onLocationUpdated(@NonNull Location location)
  {
    // Ignore any pending notifications when service is being stopping.
    final RoutingController routingController = RoutingController.get();
    if (!routingController.isNavigating())
      return;

    // Voice the turn notification first.
    final String[] turnNotifications = Framework.nativeGenerateNotifications();
    if (turnNotifications != null)
      TtsPlayer.INSTANCE.playTurnNotifications(getApplicationContext(), turnNotifications);

    // Don't spend time on updating RemoteView if notifications are not allowed.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ActivityCompat.checkSelfPermission(this, POST_NOTIFICATIONS) != PERMISSION_GRANTED)
      return;

    final RoutingInfo routingInfo = Framework.nativeGetRouteFollowingInfo();
    if (routingInfo == null)
      return;

    // The next turn.
    mRemoteViews.setImageViewResource(R.id.turn, routingInfo.carDirection.getTurnRes());

    // Distance to turn.
    mRemoteViews.setTextViewText(R.id.turn_distance, routingInfo.distToTurn.toString(this));

    // The next street.
    mRemoteViews.setTextViewText(R.id.next_street, routingInfo.nextStreet);

    // The notification object must be re-created for every update.
    mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
  }
}
