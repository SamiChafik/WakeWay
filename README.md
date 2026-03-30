# WakeWay

WakeWay is an Android application designed to help users stay awake or wake up during their commute using location-based alerts. It utilizes geofencing to trigger alarms when approaching a destination.

## Features

- **Location-based Alarms**: Set alerts that trigger when you enter a specific geographic area (Geofencing).
- **OpenStreetMap Integration**: Uses `osmdroid` for map visualization and destination selection.
- **Foreground Service**: Ensures reliable trip monitoring even when the app is in the background.
- **Wake-up Activity**: A dedicated screen for dismissing alarms, designed to work even on the lock screen.
- **Material Design**: Built with Jetpack Compose for a modern look and feel.

## Tech Stack

- **Kotlin**: Primary programming language.
- **Jetpack Compose**: For building the UI.
- **Google Play Services Location**: For geofencing and location updates.
- **osmdroid**: For offline-capable mapping.
- **Gradle**: Build system.

## Setup

1.  Clone the repository.
2.  Open the project in **Android Studio**.
3.  Ensure you have the latest **Android SDK** and **Build Tools** installed.
4.  Sync the project with Gradle files.
5.  Build and run the app on an Android device or emulator (API 26+).

## Permissions

The app requires several permissions to function correctly:
- `ACCESS_FINE_LOCATION` & `ACCESS_COARSE_LOCATION`
- `ACCESS_BACKGROUND_LOCATION` (for background geofence monitoring)
- `POST_NOTIFICATIONS` (for Android 13+)
- `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_LOCATION`
