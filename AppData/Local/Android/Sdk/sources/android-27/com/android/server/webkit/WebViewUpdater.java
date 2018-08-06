/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.webkit;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.Signature;
import android.os.UserHandle;
import android.util.Base64;
import android.util.Slog;
import android.webkit.UserPackage;
import android.webkit.WebViewFactory;
import android.webkit.WebViewProviderInfo;
import android.webkit.WebViewProviderResponse;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Class that decides what WebView implementation to use and prepares that implementation for
 * use.
 */
class WebViewUpdater {
    private static final String TAG = WebViewUpdater.class.getSimpleName();

    private static class WebViewPackageMissingException extends Exception {
        public WebViewPackageMissingException(String message) { super(message); }
        public WebViewPackageMissingException(Exception e) { super(e); }
    }

    private static final int WAIT_TIMEOUT_MS = 1000; // KEY_DISPATCHING_TIMEOUT is 5000.

    private final static int VALIDITY_OK = 0;
    private final static int VALIDITY_INCORRECT_SDK_VERSION = 1;
    private final static int VALIDITY_INCORRECT_VERSION_CODE = 2;
    private final static int VALIDITY_INCORRECT_SIGNATURE = 3;
    private final static int VALIDITY_NO_LIBRARY_FLAG = 4;

    private Context mContext;
    private SystemInterface mSystemInterface;
    private int mMinimumVersionCode = -1;

    // Keeps track of the number of running relro creations
    private int mNumRelroCreationsStarted = 0;
    private int mNumRelroCreationsFinished = 0;
    // Implies that we need to rerun relro creation because we are using an out-of-date package
    private boolean mWebViewPackageDirty = false;
    private boolean mAnyWebViewInstalled = false;

    private int NUMBER_OF_RELROS_UNKNOWN = Integer.MAX_VALUE;

    // The WebView package currently in use (or the one we are preparing).
    private PackageInfo mCurrentWebViewPackage = null;

    private final Object mLock = new Object();

    WebViewUpdater(Context context, SystemInterface systemInterface) {
        mContext = context;
        mSystemInterface = systemInterface;
    }

    void packageStateChanged(String packageName, int changedState) {
        for (WebViewProviderInfo provider : mSystemInterface.getWebViewPackages()) {
            String webviewPackage = provider.packageName;

            if (webviewPackage.equals(packageName)) {
                boolean updateWebView = false;
                boolean removedOrChangedOldPackage = false;
                String oldProviderName = null;
                PackageInfo newPackage = null;
                synchronized(mLock) {
                    try {
                        newPackage = findPreferredWebViewPackage();
                        if (mCurrentWebViewPackage != null) {
                            oldProviderName = mCurrentWebViewPackage.packageName;
                            if (changedState == WebViewUpdateService.PACKAGE_CHANGED
                                    && newPackage.packageName.equals(oldProviderName)) {
                                // If we don't change package name we should only rerun the
                                // preparation phase if the current package has been replaced
                                // (not if it has been enabled/disabled).
                                return;
                            }
                            if (newPackage.packageName.equals(oldProviderName)
                                    && (newPackage.lastUpdateTime
                                        == mCurrentWebViewPackage.lastUpdateTime)) {
                                // If the chosen package hasn't been updated, then early-out
                                return;
                            }
                        }
                        // Only trigger update actions if the updated package is the one
                        // that will be used, or the one that was in use before the
                        // update, or if we haven't seen a valid WebView package before.
                        updateWebView =
                            provider.packageName.equals(newPackage.packageName)
                            || provider.packageName.equals(oldProviderName)
                            || mCurrentWebViewPackage == null;
                        // We removed the old package if we received an intent to remove
                        // or replace the old package.
                        removedOrChangedOldPackage =
                            provider.packageName.equals(oldProviderName);
                        if (updateWebView) {
                            onWebViewProviderChanged(newPackage);
                        }
                    } catch (WebViewPackageMissingException e) {
                        mCurrentWebViewPackage = null;
                        Slog.e(TAG, "Could not find valid WebView package to create " +
                                "relro with " + e);
                    }
                }
                if(updateWebView && !removedOrChangedOldPackage
                        && oldProviderName != null) {
                    // If the provider change is the result of adding or replacing a
                    // package that was not the previous provider then we must kill
                    // packages dependent on the old package ourselves. The framework
                    // only kills dependents of packages that are being removed.
                    mSystemInterface.killPackageDependents(oldProviderName);
                }
                return;
            }
        }
    }

    void prepareWebViewInSystemServer() {
        try {
            synchronized(mLock) {
                mCurrentWebViewPackage = findPreferredWebViewPackage();
                // Don't persist the user-chosen setting across boots if the package being
                // chosen is not used (could be disabled or uninstalled) so that the user won't
                // be surprised by the device switching to using a certain webview package,
                // that was uninstalled/disabled a long time ago, if it is installed/enabled
                // again.
                mSystemInterface.updateUserSetting(mContext,
                        mCurrentWebViewPackage.packageName);
                onWebViewProviderChanged(mCurrentWebViewPackage);
            }
        } catch (Throwable t) {
            // Log and discard errors at this stage as we must not crash the system server.
            Slog.e(TAG, "error preparing webview provider from system server", t);
        }
    }

    /**
     * Change WebView provider and provider setting and kill packages using the old provider.
     * Return the new provider (in case we are in the middle of creating relro files, or
     * replacing that provider it will not be in use directly, but will be used when the relros
     * or the replacement are done).
     */
    String changeProviderAndSetting(String newProviderName) {
        PackageInfo newPackage = updateCurrentWebViewPackage(newProviderName);
        if (newPackage == null) return "";
        return newPackage.packageName;
    }

    /**
     * Update the current WebView package.
     * @param newProviderName the package to switch to, null if no package has been explicitly
     * chosen.
     */
    PackageInfo updateCurrentWebViewPackage(String newProviderName) {
        PackageInfo oldPackage = null;
        PackageInfo newPackage = null;
        boolean providerChanged = false;
        synchronized(mLock) {
            oldPackage = mCurrentWebViewPackage;

            if (newProviderName != null) {
                mSystemInterface.updateUserSetting(mContext, newProviderName);
            }

            try {
                newPackage = findPreferredWebViewPackage();
                providerChanged = (oldPackage == null)
                        || !newPackage.packageName.equals(oldPackage.packageName);
            } catch (WebViewPackageMissingException e) {
                // If updated the Setting but don't have an installed WebView package, the
                // Setting will be used when a package is available.
                mCurrentWebViewPackage = null;
                Slog.e(TAG, "Couldn't find WebView package to use " + e);
                return null;
            }
            // Perform the provider change if we chose a new provider
            if (providerChanged) {
                onWebViewProviderChanged(newPackage);
            }
        }
        // Kill apps using the old provider only if we changed provider
        if (providerChanged && oldPackage != null) {
            mSystemInterface.killPackageDependents(oldPackage.packageName);
        }
        // Return the new provider, this is not necessarily the one we were asked to switch to,
        // but the persistent setting will now be pointing to the provider we were asked to
        // switch to anyway.
        return newPackage;
    }

    /**
     * This is called when we change WebView provider, either when the current provider is
     * updated or a new provider is chosen / takes precedence.
     */
    private void onWebViewProviderChanged(PackageInfo newPackage) {
        synchronized(mLock) {
            mAnyWebViewInstalled = true;
            if (mNumRelroCreationsStarted == mNumRelroCreationsFinished) {
                mCurrentWebViewPackage = newPackage;

                // The relro creations might 'finish' (not start at all) before
                // WebViewFactory.onWebViewProviderChanged which means we might not know the
                // number of started creations before they finish.
                mNumRelroCreationsStarted = NUMBER_OF_RELROS_UNKNOWN;
                mNumRelroCreationsFinished = 0;
                mNumRelroCreationsStarted =
                    mSystemInterface.onWebViewProviderChanged(newPackage);
                // If the relro creations finish before we know the number of started creations
                // we will have to do any cleanup/notifying here.
                checkIfRelrosDoneLocked();
            } else {
                mWebViewPackageDirty = true;
            }
        }
    }

    /**
     * Fetch only the currently valid WebView packages.
     **/
    WebViewProviderInfo[] getValidWebViewPackages() {
        ProviderAndPackageInfo[] providersAndPackageInfos = getValidWebViewPackagesAndInfos();
        WebViewProviderInfo[] providers =
            new WebViewProviderInfo[providersAndPackageInfos.length];
        for(int n = 0; n < providersAndPackageInfos.length; n++) {
            providers[n] = providersAndPackageInfos[n].provider;
        }
        return providers;
    }

    private static class ProviderAndPackageInfo {
        public final WebViewProviderInfo provider;
        public final PackageInfo packageInfo;

        public ProviderAndPackageInfo(WebViewProviderInfo provider, PackageInfo packageInfo) {
            this.provider = provider;
            this.packageInfo = packageInfo;
        }
    }

    private ProviderAndPackageInfo[] getValidWebViewPackagesAndInfos() {
        WebViewProviderInfo[] allProviders = mSystemInterface.getWebViewPackages();
        List<ProviderAndPackageInfo> providers = new ArrayList<>();
        for(int n = 0; n < allProviders.length; n++) {
            try {
                PackageInfo packageInfo =
                    mSystemInterface.getPackageInfoForProvider(allProviders[n]);
                if (isValidProvider(allProviders[n], packageInfo)) {
                    providers.add(new ProviderAndPackageInfo(allProviders[n], packageInfo));
                }
            } catch (NameNotFoundException e) {
                // Don't add non-existent packages
            }
        }
        return providers.toArray(new ProviderAndPackageInfo[providers.size()]);
    }

    /**
     * Returns either the package info of the WebView provider determined in the following way:
     * If the user has chosen a provider then use that if it is valid,
     * otherwise use the first package in the webview priority list that is valid.
     *
     */
    private PackageInfo findPreferredWebViewPackage() throws WebViewPackageMissingException {
        ProviderAndPackageInfo[] providers = getValidWebViewPackagesAndInfos();

        String userChosenProvider = mSystemInterface.getUserChosenWebViewProvider(mContext);

        // If the user has chosen provider, use that (if it's installed and enabled for all
        // users).
        for (ProviderAndPackageInfo providerAndPackage : providers) {
            if (providerAndPackage.provider.packageName.equals(userChosenProvider)) {
                // userPackages can contain null objects.
                List<UserPackage> userPackages =
                        mSystemInterface.getPackageInfoForProviderAllUsers(mContext,
                                providerAndPackage.provider);
                if (isInstalledAndEnabledForAllUsers(userPackages)) {
                    return providerAndPackage.packageInfo;
                }
            }
        }

        // User did not choose, or the choice failed; use the most stable provider that is
        // installed and enabled for all users, and available by default (not through
        // user choice).
        for (ProviderAndPackageInfo providerAndPackage : providers) {
            if (providerAndPackage.provider.availableByDefault) {
                // userPackages can contain null objects.
                List<UserPackage> userPackages =
                        mSystemInterface.getPackageInfoForProviderAllUsers(mContext,
                                providerAndPackage.provider);
                if (isInstalledAndEnabledForAllUsers(userPackages)) {
                    return providerAndPackage.packageInfo;
                }
            }
        }

        // This should never happen during normal operation (only with modified system images).
        mAnyWebViewInstalled = false;
        throw new WebViewPackageMissingException("Could not find a loadable WebView package");
    }

    /**
     * Return true iff {@param packageInfos} point to only installed and enabled packages.
     * The given packages {@param packageInfos} should all be pointing to the same package, but each
     * PackageInfo representing a different user's package.
     */
    static boolean isInstalledAndEnabledForAllUsers(
            List<UserPackage> userPackages) {
        for (UserPackage userPackage : userPackages) {
            if (!userPackage.isInstalledPackage() || !userPackage.isEnabledPackage()) {
                return false;
            }
        }
        return true;
    }

    void notifyRelroCreationCompleted() {
        synchronized (mLock) {
            mNumRelroCreationsFinished++;
            checkIfRelrosDoneLocked();
        }
    }

    WebViewProviderResponse waitForAndGetProvider() {
        PackageInfo webViewPackage = null;
        final long NS_PER_MS = 1000000;
        final long timeoutTimeMs = System.nanoTime() / NS_PER_MS + WAIT_TIMEOUT_MS;
        boolean webViewReady = false;
        int webViewStatus = WebViewFactory.LIBLOAD_SUCCESS;
        synchronized (mLock) {
            webViewReady = webViewIsReadyLocked();
            while (!webViewReady) {
                final long timeNowMs = System.nanoTime() / NS_PER_MS;
                if (timeNowMs >= timeoutTimeMs) break;
                try {
                    mLock.wait(timeoutTimeMs - timeNowMs);
                } catch (InterruptedException e) {}
                webViewReady = webViewIsReadyLocked();
            }
            // Make sure we return the provider that was used to create the relro file
            webViewPackage = mCurrentWebViewPackage;
            if (webViewReady) {
            } else if (!mAnyWebViewInstalled) {
                webViewStatus = WebViewFactory.LIBLOAD_FAILED_LISTING_WEBVIEW_PACKAGES;
            } else {
                // Either the current relro creation  isn't done yet, or the new relro creatioin
                // hasn't kicked off yet (the last relro creation used an out-of-date WebView).
                webViewStatus = WebViewFactory.LIBLOAD_FAILED_WAITING_FOR_RELRO;
                Slog.e(TAG, "Timed out waiting for relro creation, relros started "
                        + mNumRelroCreationsStarted
                        + " relros finished " + mNumRelroCreationsFinished
                        + " package dirty? " + mWebViewPackageDirty);
            }
        }
        if (!webViewReady) Slog.w(TAG, "creating relro file timed out");
        return new WebViewProviderResponse(webViewPackage, webViewStatus);
    }

    PackageInfo getCurrentWebViewPackage() {
        synchronized(mLock) {
            return mCurrentWebViewPackage;
        }
    }

    /**
     * Returns whether WebView is ready and is not going to go through its preparation phase
     * again directly.
     */
    private boolean webViewIsReadyLocked() {
        return !mWebViewPackageDirty
            && (mNumRelroCreationsStarted == mNumRelroCreationsFinished)
            // The current package might be replaced though we haven't received an intent
            // declaring this yet, the following flag makes anyone loading WebView to wait in
            // this case.
            && mAnyWebViewInstalled;
    }

    private void checkIfRelrosDoneLocked() {
        if (mNumRelroCreationsStarted == mNumRelroCreationsFinished) {
            if (mWebViewPackageDirty) {
                mWebViewPackageDirty = false;
                // If we have changed provider since we started the relro creation we need to
                // redo the whole process using the new package instead.
                try {
                    PackageInfo newPackage = findPreferredWebViewPackage();
                    onWebViewProviderChanged(newPackage);
                } catch (WebViewPackageMissingException e) {
                    mCurrentWebViewPackage = null;
                    // If we can't find any valid WebView package we are now in a state where
                    // mAnyWebViewInstalled is false, so loading WebView will be blocked and we
                    // should simply wait until we receive an intent declaring a new package was
                    // installed.
                }
            } else {
                mLock.notifyAll();
            }
        }
    }

    /**
     * Returns whether this provider is valid for use as a WebView provider.
     */
    boolean isValidProvider(WebViewProviderInfo configInfo, PackageInfo packageInfo) {
        return VALIDITY_OK == validityResult(configInfo, packageInfo);
    }

    private int validityResult(WebViewProviderInfo configInfo, PackageInfo packageInfo) {
        // Ensure the provider targets this framework release (or a later one).
        if (!UserPackage.hasCorrectTargetSdkVersion(packageInfo)) {
            return VALIDITY_INCORRECT_SDK_VERSION;
        }
        if (!versionCodeGE(packageInfo.versionCode, getMinimumVersionCode())
                && !mSystemInterface.systemIsDebuggable()) {
            // Webview providers may be downgraded arbitrarily low, prevent that by enforcing
            // minimum version code. This check is only enforced for user builds.
            return VALIDITY_INCORRECT_VERSION_CODE;
        }
        if (!providerHasValidSignature(configInfo, packageInfo, mSystemInterface)) {
            return VALIDITY_INCORRECT_SIGNATURE;
        }
        if (WebViewFactory.getWebViewLibrary(packageInfo.applicationInfo) == null) {
            return VALIDITY_NO_LIBRARY_FLAG;
        }
        return VALIDITY_OK;
    }

    /**
     * Both versionCodes should be from a WebView provider package implemented by Chromium.
     * VersionCodes from other kinds of packages won't make any sense in this method.
     *
     * An introduction to Chromium versionCode scheme:
     * "BBBBPPPAX"
     * BBBB: 4 digit branch number. It monotonically increases over time.
     * PPP: patch number in the branch. It is padded with zeroes to the left. These three digits
     * may change their meaning in the future.
     * A: architecture digit.
     * X: A digit to differentiate APKs for other reasons.
     *
     * This method takes the "BBBB" of versionCodes and compare them.
     *
     * @return true if versionCode1 is higher than or equal to versionCode2.
     */
    private static boolean versionCodeGE(int versionCode1, int versionCode2) {
        int v1 = versionCode1 / 100000;
        int v2 = versionCode2 / 100000;

        return v1 >= v2;
    }

    /**
     * Gets the minimum version code allowed for a valid provider. It is the minimum versionCode
     * of all available-by-default and non-fallback WebView provider packages. If there is no
     * such WebView provider package on the system, then return -1, which means all positive
     * versionCode WebView packages are accepted.
     *
     * Note that this is a private method in WebViewUpdater that handles a variable
     * (mMinimumVersionCode) which is shared between threads. Furthermore, this method does not
     * hold mLock meaning that we must take extra care to ensure this method is thread-safe.
     */
    private int getMinimumVersionCode() {
        if (mMinimumVersionCode > 0) {
            return mMinimumVersionCode;
        }

        int minimumVersionCode = -1;
        for (WebViewProviderInfo provider : mSystemInterface.getWebViewPackages()) {
            if (provider.availableByDefault && !provider.isFallback) {
                try {
                    int versionCode =
                        mSystemInterface.getFactoryPackageVersion(provider.packageName);
                    if (minimumVersionCode < 0 || versionCode < minimumVersionCode) {
                        minimumVersionCode = versionCode;
                    }
                } catch (NameNotFoundException e) {
                    // Safe to ignore.
                }
            }
        }

        mMinimumVersionCode = minimumVersionCode;
        return mMinimumVersionCode;
    }

    private static boolean providerHasValidSignature(WebViewProviderInfo provider,
            PackageInfo packageInfo, SystemInterface systemInterface) {
        if (systemInterface.systemIsDebuggable()) {
            return true;
        }
        Signature[] packageSignatures;
        // If no signature is declared, instead check whether the package is included in the
        // system.
        if (provider.signatures == null || provider.signatures.length == 0) {
            return packageInfo.applicationInfo.isSystemApp();
        }
        packageSignatures = packageInfo.signatures;
        if (packageSignatures.length != 1)
            return false;

        final byte[] packageSignature = packageSignatures[0].toByteArray();
        // Return whether the package signature matches any of the valid signatures
        for (String signature : provider.signatures) {
            final byte[] validSignature = Base64.decode(signature, Base64.DEFAULT);
            if (Arrays.equals(packageSignature, validSignature))
                return true;
        }
        return false;
    }

    void dumpState(PrintWriter pw) {
        synchronized (mLock) {
            if (mCurrentWebViewPackage == null) {
                pw.println("  Current WebView package is null");
            } else {
                pw.println(String.format("  Current WebView package (name, version): (%s, %s)",
                        mCurrentWebViewPackage.packageName,
                        mCurrentWebViewPackage.versionName));
            }
            pw.println(String.format("  Minimum WebView version code: %d",
                  mMinimumVersionCode));
            pw.println(String.format("  Number of relros started: %d",
                    mNumRelroCreationsStarted));
            pw.println(String.format("  Number of relros finished: %d",
                        mNumRelroCreationsFinished));
            pw.println(String.format("  WebView package dirty: %b", mWebViewPackageDirty));
            pw.println(String.format("  Any WebView package installed: %b",
                    mAnyWebViewInstalled));

            try {
                PackageInfo preferredWebViewPackage = findPreferredWebViewPackage();
                pw.println(String.format(
                        "  Preferred WebView package (name, version): (%s, %s)",
                        preferredWebViewPackage.packageName,
                        preferredWebViewPackage.versionName));
            } catch (WebViewPackageMissingException e) {
                pw.println(String.format("  Preferred WebView package: none"));
            }

            dumpAllPackageInformationLocked(pw);
        }
    }

    private void dumpAllPackageInformationLocked(PrintWriter pw) {
        WebViewProviderInfo[] allProviders = mSystemInterface.getWebViewPackages();
        pw.println("  WebView packages:");
        for (WebViewProviderInfo provider : allProviders) {
            List<UserPackage> userPackages =
                    mSystemInterface.getPackageInfoForProviderAllUsers(mContext, provider);
            PackageInfo systemUserPackageInfo =
                    userPackages.get(UserHandle.USER_SYSTEM).getPackageInfo();
            if (systemUserPackageInfo == null) {
                continue;
            }

            int validity = validityResult(provider, systemUserPackageInfo);
            String packageDetails = String.format(
                    "versionName: %s, versionCode: %d, targetSdkVersion: %d",
                    systemUserPackageInfo.versionName,
                    systemUserPackageInfo.versionCode,
                    systemUserPackageInfo.applicationInfo.targetSdkVersion);
            if (validity == VALIDITY_OK) {
                boolean installedForAllUsers = isInstalledAndEnabledForAllUsers(
                        mSystemInterface.getPackageInfoForProviderAllUsers(mContext, provider));
                pw.println(String.format(
                        "    Valid package %s (%s) is %s installed/enabled for all users",
                        systemUserPackageInfo.packageName,
                        packageDetails,
                        installedForAllUsers ? "" : "NOT"));
            } else {
                pw.println(String.format("    Invalid package %s (%s), reason: %s",
                        systemUserPackageInfo.packageName,
                        packageDetails,
                        getInvalidityReason(validity)));
            }
        }
    }

    private static String getInvalidityReason(int invalidityReason) {
        switch (invalidityReason) {
            case VALIDITY_INCORRECT_SDK_VERSION:
                return "SDK version too low";
            case VALIDITY_INCORRECT_VERSION_CODE:
                return "Version code too low";
            case VALIDITY_INCORRECT_SIGNATURE:
                return "Incorrect signature";
            case VALIDITY_NO_LIBRARY_FLAG:
                return "No WebView-library manifest flag";
            default:
                return "Unexcepted validity-reason";
        }
    }

}
