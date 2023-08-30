AWARE-Light Framework for Android Client
======================

AWARE-Light is an Android framework dedicated to instrument, infer, log and share mobile context information,
for application developers, researchers and smartphone users. AWARE-Light captures hardware-, software-, and 
human-based data. It encapsulates analysis, machine learning and simplifies conducting user studies 
in naturalistic and laboratory settings. The platform can be integrated with MySQL database.


# Getting Started

You can get the source code of all the components that make the AWARE-Light client from GitHub.
```bash
$ git clone https://github.com/awareframework/aware-light-client.git
$ cd aware-light-client
$ touch .gitmodules
$ git submodule update --init
```

# Contributing
If you would like to contribute to AWARE-Light, please contact Dr [Simon D'Alfonso](https://findanexpert.unimelb.edu.au/profile/180658-simon-d'alfonso)  or Dr [Vassilis Kostakos](https://people.eng.unimelb.edu.au/vkostakos/).


# App features
Note that the imported external plugins are not implemented in the code and can be used only with proper implementation which will vary for different studies and researchers.

## aware-core
`aware-core` contains the essential components that handle the core functionalities of the AWARE framework. This part focuses on collecting, storing, and synchronizing data from various sensors and sources. It includes modules responsible for interacting with sensors, capturing data, managing the local database, and ensuring data synchronization with remote servers. The core functionality ensures that data is efficiently and accurately collected, stored, and made available for further analysis.
* `src/main/java/com.aware/providers`: Database information
* `src/main/java/com.aware/syncadapters`: Synchronization information
* `src/main/java/com.aware`: data collection for various data
* `src/aware.gradle`: basic setting, including versioning

## aware-phone
`aware-phone` folder is dedicated to the user interface (UI) and client-side aspects of the AWARE framework. This component provides the user-facing side of the application, allowing users to interact with the framework through a user-friendly interface. The aware-phone module handles tasks such as displaying data visualizations, providing settings and configuration options, managing user accounts, and presenting data to users in a meaningful way. This part ensures that users have a seamless and intuitive experience while interacting with the AWARE framework.
* `src/main/java/com.aware.phone/ui`: join study UI
* `src/main/java/com.aware.phone/util`: not used

## aware-test
Basic testing code.


# Troubleshooting

1. Checking build version

Please make sure that the build.gradle in the project level and the aware-core level is using the appropriate version of Java and Gradle.

2. SDK version

Please make sure the Android SDK is pointing the correct directory and is selected at least 28.

3. Please select aware-phone module to run with in configurations



# Frequent Issues

## AWARE-Light flicking after installation
Please make sure:
1) all the permissions are granted to AWARE-Light
2) location and bluetooth are turned on before installing AWARE-Light

## Error message that AWARE-Light incompatible with the phone
For phones with Android 13+, please generate APK file with 'arm64-v8a'.

## AWARE-Light not collecting data
* The main idea is to keep the application run in the background and prevent system setting from killing it. Battery optimization and locking it in the system menu is needed.
Here are some general steps, but please note that the specific steps may vary depending on your phone model:
- Go to Setting -> Battery -> Scenario AI-optimization -> Sleep mode needs to be off
- Go to Setting -> Battery -> Scenario AI-optimization -> Apps AI-control needs to be off
- Go to Setting -> Battery -> Power-saver needs to be off
- Go to Setting -> Battery -> More Settings -> Power-saver settings -> Turn on automatically needs to be off
- Go to Setting -> Battery -> More Settings -> Power-saver settings -> Restrict apps from accessing network needs to be off

* Accessibility needs to be turned on for AWARE-Light for special sensors to work: Applications, Touch, Notifications, Keyboard, Crash logs, Screen reader

* Accessibility cannot be turned on (a helpful [demonstration video](https://www.youtube.com/watch?v=0bcLjpfrmHw)):
1) On your Android device, open the Settings app.
2) Tap Apps.
3) Tap the app that you want to turn on a restricted setting for. (If you can't find it, first tap See all apps or App info.)
4) Tap More and then Allow restricted settings.
5) Follow the on-screen instructions.


Open-source (Apache 2.0)
========================
Copyright (c) 2011 AWARE Mobile Context Instrumentation Middleware/Framework [https://www.awareframework.com](http://www.awareframework.com)

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at 
http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.