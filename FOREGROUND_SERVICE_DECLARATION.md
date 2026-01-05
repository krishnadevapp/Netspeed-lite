# Foreground Service Declaration for Google Play Console

## Foreground Service Type Used
**Type:** `FOREGROUND_SERVICE_DATA_SYNC` (dataSync)

## Purpose of Foreground Service

Our app uses a foreground service to continuously monitor and display real-time network speed and data usage statistics in the notification bar. This service is essential for the core functionality of the app.

### Specific Use Cases:

1. **Real-time Network Speed Monitoring**
   - Continuously monitors network traffic (upload/download speeds)
   - Updates the notification icon with current speed values every second
   - Provides users with instant visibility of their network performance

2. **Data Usage Statistics Collection**
   - Tracks mobile and WiFi data usage in real-time
   - Collects network statistics for daily, weekly, and monthly usage reports
   - Syncs usage data to provide accurate data consumption tracking

3. **Background Data Monitoring**
   - Monitors network activity even when the app is in the background
   - Ensures users can see their current network speed without keeping the app open
   - Maintains accurate data usage statistics throughout the day

## Why Foreground Service is Required

- **Continuous Monitoring**: Network speed and data usage need to be monitored continuously, not just when the app is open
- **Real-time Updates**: The notification icon displays live speed data that updates every second
- **Background Operation**: Users expect to see their network speed in the notification bar even when using other apps
- **Data Accuracy**: Accurate data usage tracking requires continuous monitoring of network statistics

## User Benefit

- Users can monitor their network speed at all times without opening the app
- Real-time visibility helps users understand their network performance
- Accurate data usage tracking helps users manage their data consumption
- The service provides essential functionality that users explicitly enable through app settings

## Service Declaration in Manifest

```xml
<service
    android:name=".SpeedService"
    android:enabled="true"
    android:exported="false"
    android:foregroundServiceType="dataSync">
</service>
```

## Permissions Used

- `FOREGROUND_SERVICE` - Required for all foreground services
- `FOREGROUND_SERVICE_DATA_SYNC` - Required for data synchronization services
- `PACKAGE_USAGE_STATS` - Required to access network usage statistics (user-granted permission)

## User Control

- Users can enable/disable the foreground service through app settings
- The service only runs when explicitly enabled by the user
- Users can stop the service at any time through the app's "Stop and Exit" option
- The service requires user permission to access usage statistics

## Compliance

This use case is compliant with Google Play's Foreground Service policy as:
- The service is essential for the app's core functionality (network speed monitoring)
- The service type `dataSync` is appropriate for syncing/transferring network data
- Users have full control over enabling/disabling the service
- The service provides clear value to users (real-time network monitoring)


