package com.android.niap.cert.manager;

/**
 * Asynchronous result channel for INiapCertManager.requestRevocation.
 *
 * The service invokes exactly one of onSuccess / onError per revocation.
 * If the service dies before either is called, the caller-side
 * IBinder.linkToDeath() hook synthesises an onError.
 */
oneway interface IRevocationCallback {
    void onSuccess();
    void onError(String errorMessage);
}
